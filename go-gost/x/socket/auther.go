package socket

import (
	"errors"
	"fmt"
	"strings"

	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/auth"
	"github.com/go-gost/x/registry"
)

type createAutherRequest struct {
	Data config.AutherConfig `json:"data"`
}
type deleteAutherRequest struct {
	Auther string `json:"auther"`
}

func createAuther(req createAutherRequest) error {
	name := strings.TrimSpace(req.Data.Name)
	if name == "" {
		return errors.New("auther name is required")
	}
	req.Data.Name = name
	if registry.AutherRegistry().IsRegistered(name) {
		return fmt.Errorf("auther %s already exists", name)
	}
	if err := registry.AutherRegistry().Register(name, parser.ParseAuther(&req.Data)); err != nil {
		return err
	}
	config.OnUpdate(func(c *config.Config) error { c.Authers = append(c.Authers, &req.Data); return nil })
	return nil
}

func deleteAuther(req deleteAutherRequest) error {
	name := strings.TrimSpace(req.Auther)
	if !registry.AutherRegistry().IsRegistered(name) {
		return fmt.Errorf("auther %s not found", name)
	}
	registry.AutherRegistry().Unregister(name)
	config.OnUpdate(func(c *config.Config) error {
		authers := c.Authers
		c.Authers = nil
		for _, auther := range authers {
			if auther.Name != name {
				c.Authers = append(c.Authers, auther)
			}
		}
		return nil
	})
	return nil
}
