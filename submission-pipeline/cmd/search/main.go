// Command search queries the article search index from the shell, refreshes it
// on demand, and prints the SQL that creates it. It talks to Postgres directly,
// so it needs nothing but a connection string.
//
//	search -q "تنگه هرمز" -lang fa      run a query
//	search -sync                       bring the index up to date now
//	search -schema                     print the SQL, for psql or a Flyway migration
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/jackc/pgx/v5/pgxpool"

	"wikipg/internal/search"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	dsn := flag.String("dsn", os.Getenv("SEARCH_DSN"), "Postgres connection string (default $SEARCH_DSN)")
	query := flag.String("q", "", "text to search for")
	language := flag.String("lang", "", "only this language, e.g. fa (default: all)")
	entityType := flag.String("type", "", "only this entity type, e.g. ISLAND (default: all)")
	limit := flag.Int("limit", search.DefaultLimit, "results per page, at most 100")
	offset := flag.Int("offset", 0, "results to skip")
	sync := flag.Bool("sync", false, "refresh the index before anything else")
	schema := flag.Bool("schema", false, "print the SQL that creates the index and exit")
	flag.Parse()

	if *schema {
		fmt.Print(search.Schema)
		return nil
	}
	if *dsn == "" {
		return fmt.Errorf("-dsn or SEARCH_DSN is required")
	}
	if !*sync && strings.TrimSpace(*query) == "" {
		return fmt.Errorf("-q is required, unless -sync or -schema is given")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, *dsn)
	if err != nil {
		return fmt.Errorf("connecting: %w", err)
	}
	defer pool.Close()

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	// Snippets are HTML. The encoder would otherwise rewrite every angle bracket
	// in them as a Unicode escape: valid JSON, but unreadable in a terminal,
	// which is where this output is read.
	enc.SetEscapeHTML(false)

	if *sync {
		var total struct {
			Indexed int `json:"indexed"`
			Removed int `json:"removed"`
		}
		const batch = 500
		for {
			indexed, removed, err := search.Reconcile(ctx, pool, batch)
			if err != nil {
				return err
			}
			total.Indexed += indexed
			total.Removed += removed
			if indexed+removed < batch {
				break
			}
		}
		if strings.TrimSpace(*query) == "" {
			return enc.Encode(total)
		}
		fmt.Fprintf(os.Stderr, "synced: %d indexed, %d removed\n", total.Indexed, total.Removed)
	}

	page, err := search.Search(ctx, pool, search.Query{
		Text:       *query,
		Language:   *language,
		EntityType: *entityType,
		Limit:      *limit,
		Offset:     *offset,
	})
	if err != nil {
		return err
	}
	return enc.Encode(page)
}
