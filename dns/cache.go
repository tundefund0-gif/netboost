package dns

import (
	"container/list"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type entry struct {
	key   string
	data  []byte
	until time.Time
}

type Cache struct {
	mu     sync.RWMutex
	m      map[string]*list.Element
	lru    *list.List
	max    int
	hits   atomic.Int64
	miss   atomic.Int64
	queries atomic.Int64
}

func NewCache(max int) *Cache {
	return &Cache{
		m:   make(map[string]*list.Element),
		lru: list.New(),
		max: max,
	}
}

func (c *Cache) Get(name string, qtype uint16) ([]byte, bool) {
	key := fmt.Sprintf("%s:%d", name, qtype)
	c.mu.RLock()
	e, ok := c.m[key]
	if !ok {
		c.mu.RUnlock()
		c.miss.Add(1)
		return nil, false
	}
	ent := e.Value.(*entry)
	if time.Now().After(ent.until) {
		c.mu.RUnlock()
		c.mu.Lock()
		c.lru.Remove(e)
		delete(c.m, key)
		c.mu.Unlock()
		c.miss.Add(1)
		return nil, false
	}
	c.mu.RUnlock()
	c.mu.Lock()
	c.lru.MoveToFront(e)
	c.mu.Unlock()
	c.hits.Add(1)
	r := make([]byte, len(ent.data))
	copy(r, ent.data)
	return r, true
}

func (c *Cache) Set(name string, qtype uint16, data []byte, ttl time.Duration) {
	key := fmt.Sprintf("%s:%d", name, qtype)
	ent := &entry{key: key, data: data, until: time.Now().Add(ttl)}
	c.mu.Lock()
	defer c.mu.Unlock()
	if e, ok := c.m[key]; ok {
		c.lru.MoveToFront(e)
		e.Value = ent
		return
	}
	for c.lru.Len() >= c.max {
		b := c.lru.Back()
		if b == nil {
			break
		}
		c.lru.Remove(b)
		delete(c.m, b.Value.(*entry).key)
	}
	c.m[key] = c.lru.PushFront(ent)
}

func (c *Cache) CacheStats() (hits, miss int64) {
	return c.hits.Load(), c.miss.Load()
}

func (s *Server) Stats() (queries, hits, miss int64) {
	return s.qCount.Load(), s.hCount.Load(), s.mCount.Load()
}

type UpstreamType int

const (
	UpstreamUDP UpstreamType = iota
	UpstreamTLS
)

type Server struct {
	cache    *Cache
	upAddr   string
	upType   UpstreamType
	upHost   string
	conn     *net.UDPConn
	quit     chan struct{}
	wg       sync.WaitGroup
	forward  chan forwardJob
	qCount   atomic.Int64
	hCount   atomic.Int64
	mCount   atomic.Int64
}

type forwardJob struct {
	msg  []byte
	resp chan forwardResult
}

type forwardResult struct {
	msg []byte
	err error
}

func NewServer(cache *Cache, upAddr string, upType UpstreamType, upHost string) *Server {
	return &Server{
		cache:   cache,
		upAddr:  upAddr,
		upType:  upType,
		upHost:  upHost,
		forward: make(chan forwardJob, 4096),
		quit:    make(chan struct{}),
	}
}

func (s *Server) Start(addr string) error {
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		return err
	}
	s.conn = conn

	for i := 0; i < 4; i++ {
		s.wg.Add(1)
		go s.forwardWorker()
	}

	s.wg.Add(1)
	go s.serve()
	return nil
}

func (s *Server) Stop() {
	close(s.quit)
	if s.conn != nil {
		s.conn.Close()
	}
	s.wg.Wait()
}

func (s *Server) serve() {
	defer s.wg.Done()
	buf := make([]byte, 512)
	for {
		select {
		case <-s.quit:
			return
		default:
		}
		n, client, err := s.conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		s.qCount.Add(1)
		msg := make([]byte, n)
		copy(msg, buf[:n])
		go s.handle(msg, client)
	}
}

func (s *Server) handle(msg []byte, client *net.UDPAddr) {
	name, qtype := parseQuestion(msg)
	if name != "" {
		if cached, ok := s.cache.Get(name, qtype); ok {
			s.hCount.Add(1)
			s.conn.WriteToUDP(cached, client)
			return
		}
	}
	s.mCount.Add(1)
	s.forwardAndRespond(msg, client, name, qtype)
}

func (s *Server) forwardAndRespond(msg []byte, client *net.UDPAddr, name string, qtype uint16) {
	respCh := make(chan forwardResult, 1)
	s.forward <- forwardJob{msg: msg, resp: respCh}
	select {
	case r := <-respCh:
		if r.err == nil && len(r.msg) > 0 {
			if name != "" {
				ttl := extractTTL(r.msg)
				if ttl > 0 {
					s.cache.Set(name, qtype, r.msg, ttl)
				}
			}
			s.conn.WriteToUDP(r.msg, client)
		}
	case <-time.After(5 * time.Second):
	}
}

func (s *Server) forwardWorker() {
	defer s.wg.Done()
	for {
		select {
		case <-s.quit:
			return
		case job := <-s.forward:
			var resp []byte
			var err error
			switch s.upType {
			case UpstreamTLS:
				resp, err = forwardDoT(job.msg, s.upAddr, s.upHost)
			default:
				resp, err = forwardUDP(job.msg, s.upAddr)
			}
			job.resp <- forwardResult{msg: resp, err: err}
		}
	}
}

func forwardUDP(msg []byte, addr string) ([]byte, error) {
	conn, err := net.DialTimeout("udp", addr, 3*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(msg); err != nil {
		return nil, err
	}
	resp := make([]byte, 512)
	n, err := conn.Read(resp)
	if err != nil {
		return nil, err
	}
	return resp[:n], nil
}

func forwardDoT(msg []byte, addr, hostname string) ([]byte, error) {
	conf := &tls.Config{ServerName: hostname}
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 3 * time.Second}, "tcp", addr, conf)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))

	// DNS-over-TLS: 2-byte length prefix
	wire := make([]byte, 2+len(msg))
	binary.BigEndian.PutUint16(wire, uint16(len(msg)))
	copy(wire[2:], msg)

	if _, err := conn.Write(wire); err != nil {
		return nil, err
	}

	// Read 2-byte length
	lenBuf := make([]byte, 2)
	if _, err := conn.Read(lenBuf); err != nil {
		return nil, err
	}
	respLen := binary.BigEndian.Uint16(lenBuf)
	resp := make([]byte, respLen)
	if _, err := conn.Read(resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func parseQuestion(msg []byte) (string, uint16) {
	if len(msg) < 12 {
		return "", 0
	}
	off := 12
	var labels []byte
	for off < len(msg) {
		b := msg[off]
		if b == 0 {
			off++
			break
		}
		if b&0xC0 == 0xC0 {
			break
		}
		l := int(b)
		if off+1+l >= len(msg) {
			return "", 0
		}
		labels = append(labels, msg[off:off+1+l]...)
		off += 1 + l
	}
	if off+4 > len(msg) {
		return "", 0
	}
	qtype := binary.BigEndian.Uint16(msg[off:])
	return string(labels), qtype
}

func extractTTL(msg []byte) time.Duration {
	if len(msg) < 12 {
		return 60 * time.Second
	}
	off := 12
	for off < len(msg) {
		b := msg[off]
		if b == 0 {
			off++
			break
		}
		if b&0xC0 == 0xC0 {
			off += 2
			break
		}
		off += 1 + int(b)
		if off >= len(msg) {
			return 60 * time.Second
		}
	}
	off += 4
	for off+12 <= len(msg) {
		if msg[off]&0xC0 == 0xC0 {
			off += 2
		} else {
			for off < len(msg) && msg[off] != 0 {
				off++
			}
			off++
		}
		if off+10 > len(msg) {
			break
		}
		ttl := binary.BigEndian.Uint32(msg[off+4:])
		rdlen := binary.BigEndian.Uint16(msg[off+8:])
		off += 10 + int(rdlen)
		if ttl > 0 {
			d := time.Duration(ttl) * time.Second
			if d > 24*time.Hour {
				d = 24 * time.Hour
			}
			if d < 1*time.Second {
				d = 1 * time.Second
			}
			return d
		}
	}
	return 60 * time.Second
}
