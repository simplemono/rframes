# rframes on a Cloudflare Worker

This module runs the loop of rframes on a Cloudflare Worker with a Durable
Object. The browser half stays as it is. A page opens its frame stream
with a POST, reads server-sent events from it and posts its commands, so
the client module serves a Worker as it serves babashka.

The server is two halves here.

```
 browser                    Worker                       Durable Object
                        +-----------------+          +---------------------+
 POST /         ------> | relay           | -------> | accepts a WebSocket |
 frame stream   <------ | SSE, brotli     | <------- | per tab, sends a    |
                        |                 |  frames  | frame per change    |
 POST /command  ------> | hands it on     | -------> | runs the command    |
                        +-----------------+          +---------------------+
                          keeps no state               state, views, sleep
```

The Object holds the state, runs the commands and renders the views. The
Worker answers the browser. It serves the static shell of a page, holds
the frame stream of every tab and hands the commands on.

The split follows from how Cloudflare bills. An Object that holds a
response open stays in memory, and every second of that counts.
An Object whose only connections are WebSockets it accepted through the
hibernation API may sleep while they stay open. So the Worker holds the
stream to the browser, and a WebSocket to the Object. Between two
changes the Object sleeps and the page stays connected. A Worker pays
for CPU time, not for the time a stream is open.

A probe of this design in plain JavaScript ran on Cloudflare's network
before the module existed. A frame of 116 kB that differed from the one
before it in one number took 19 to 22 bytes. The Worker in the middle
added about 3 ms. After 150 s without a change the stream was still
open, the Object had slept, and the next frame came on the same stream.
The module's own example has run inside workerd, see Tests.

## Install

A [clojurific](https://github.com/WhimsicalCode/clojurific) project
takes the module in its `deps.edn`:

```clojure
io.github.simplemono/rframes$worker
{:git/url "https://github.com/simplemono/rframes.git"
 :git/sha "<sha>"
 :deps/root "worker"}
```

The page's script is the client module, as with every rframes server.

## The register

Both halves read one register, with the entries the server module has:

```clojure
(def register
  [{:replicant/shim "/"                ; GET: the static shell of the page
    :head "<link rel=\"stylesheet\" href=\"/app.css\"><script type=\"module\" src=\"/js/main.js\"></script>"}
   {:replicant/render "/"              ; POST: the frame stream of the page
    :render/fn #'page-view}
   {:command/kind :note/add            ; a command the browser may issue
    :command/fn #'add-note}
   {:ring/route [:get "/health"]       ; a route of the Worker, w -> w
    :ring/handler #'health}])
```

The Worker serves the shim and the routes, relays the stream of a render
entry and hands on every POST to a path that ends in `/command`. The
Object renders with `:render/fn` and runs `:command/fn`. A view function
and a command function look as they do on babashka:

```clojure
(defn page-view [{:keys [db tabid]}]
  (let [{:keys [notes]} @db]
    [:main
     [:ul (for [[i note] (map-indexed vector notes)]
            [:li {:replicant/key i} note])]]))

(defn add-note [w]
  (let [text (:text (command/command-data w))]
    (if (str/blank? text)
      (command/rejected w :empty-note)
      (do (swap! (:db w) update :notes conj text)
          (command/accepted w)))))
```

The one difference is where `db` comes from. It is in the world, not in
a namespace. The next section says why.

## The Object

`defobject` defines the Durable Object class. Its options are a map, or
a function of the constructor's `ctx` and `env` that answers the map,
for state made per Object:

```clojure
(ns my.worker
  (:require [rframes.worker :as worker]
            [rframes.worker.durable :refer [defobject]]
            [rframes.worker.object :as object]))

(defobject Page
  (fn [^js ctx _env]
    (let [db (atom (load-db ctx))]
      (add-watch db ::save (fn [_ _ _ value] (save-db! ctx value)))
      {:get-register (fn [] register)
       :world (fn [] {:db db})
       :init (fn [page] (object/watch! page db))})))
```

Three rules come with a Durable Object.

**Nothing at namespace level.** Objects of one class share an isolate
and with it every `def`. In a test, a map at module level written by one
Object was readable in another. A `(defonce db (atom ...))` would be
every organization's state at once. So the state belongs to the
instance, and `:world` hands it to the views and commands.

**Memory does not last.** An Object nobody talks to sleeps after about
ten seconds. Its memory goes, its sockets stay, and the constructor runs
again at the next event. So the state lives in the Object's storage. The
example reads one row when it wakes and writes it back at every change.
An event log in the Object's SQLite does the same job with a history.

**A connection is data.** What the Object knows about a tab rides on the
socket: the page's path and query string, the tab's id and what the
Worker's `:connection` said about who asks. It is plain data of at most
16 kB. A connection with more than that gets no stream.

The world of a view and of a command function:

| key | |
| --- | --- |
| `:system/get-register` | the register |
| `:object/page` | the page, for `object/schedule-refresh!` |
| `:object/ctx` | the Object's state, storage under `.-storage` |
| `:object/env` | the bindings |
| `:ring/request` | `:uri` and `:query-string` of the page, or of the command |
| `:ring/route-params` | of the render entry, in a view |
| `:conn-id`, `:tabid` | of the connection, in a view |

Under them is what `:world` answers, and over that what the Worker's
`:connection` told.

The Object renders its open pages after every command. A page the
command left as it was gets no frame, so a command function only has to
change the state. For a change that no command made, an alarm or the
answer of a `fetch`, there is `(object/watch! page db)` for an atom and
`(object/schedule-refresh! page)` for anything else. Refreshes are
coalesced to ten per second. `(object/refresh-where! page pred)` renders
the connections whose world `pred` picks.

An Object with more methods than the three of `defobject` is a class of
its own. `rframes.worker.object` has `fetch` and `closed!` for it, and
knows no class.

## The Worker

```clojure
(def handler
  #js {:fetch (worker/fetch-handler
               {:get-register (fn [] register)
                :object (fn [{:keys [worker/env]}]
                          (let [^js pages (.-PAGE ^js env)]
                            (.get pages (.idFromName pages "acme"))))})})
```

| option | |
| --- | --- |
| `:get-register` | a function that answers the register |
| `:object` | a function of the world that answers the stub of the Object a request belongs to, nil for none |
| `:connection` | a function of the world that answers plain data about who asks, or a promise of it; optional |
| `:heartbeat-ms` | the pause between two comment lines on a quiet stream; 25 s when missing, nil for none |
| `:brotli` | `{:lgwin :quality}` of the stream's encoder; a 1 MB window and quality 5 when missing |

`:object` is where a session decides. The world has `:ring/request`,
`:worker/env` and `:worker/request`. Take the organization from the
session and the Object from the organization. A browser that may name
its Object reads another customer's page.

`:connection` is how the Object learns who asks. What it answers reaches
the world of the views and commands, so a view can show an admin more
than a member. A command function has to check the role itself. A button
the view left out is no check.

## The stream

The stream is compressed as one and flushed after every frame, so a
frame that resembles the one before it costs a few bytes. The Worker
uses brotli where the browser takes it and gzip otherwise.

Brotli matters once a frame grows. Gzip looks back 32 kB. A frame longer
than that finds nothing of the last one in reach and costs its full size
again. In the probe a 23 kB frame took about 200 bytes per change with
gzip and about 20 with brotli. A 46 kB frame took 22.9 kB per change
with gzip, and still about 20 bytes with brotli.

Cloudflare replaces the `Accept-Encoding` header before a Worker sees
it. The relay reads `request.cf.clientAcceptEncoding`, which keeps what
the browser sent.

A frame equal to the last one is not sent. The Object checks before it
sends, and the Worker checks again, because the Object forgets what it
sent when it sleeps.

A quiet stream carries a comment line every 25 s, `:heartbeat-ms`. It
keeps whatever sits between the Worker and the browser from cutting an
idle connection, and it is also how the relay learns that a tab is
gone. A client that reset its connection was noticed within a second.
Chromium closing a tab over HTTP/3 was not, the relay found out when it
wrote the next heartbeat, 24 s later. Under `wrangler dev` it takes two
heartbeats. So a gone tab can stay among the Object's connections for
up to one heartbeat. It costs nothing there, since the heartbeat never
reaches the Object and an orphaned socket does not keep it awake, but a
page that shows who is connected lags by that much. Such a page wants a
shorter heartbeat.

## wrangler.jsonc and the build

```jsonc
{
  "main": "dist/worker/example.js",
  "compatibility_date": "2026-10-01",
  "compatibility_flags": ["nodejs_compat", "enable_request_signal"],
  "assets": { "directory": "./dist/public" },
  "durable_objects": { "bindings": [{ "name": "PAGE", "class_name": "Page" }] },
  "migrations": [{ "tag": "v1", "new_sqlite_classes": ["Page"] }]
}
```

`nodejs_compat` is for `node:zlib`, the encoder of the stream.
`enable_request_signal` aborts the request's signal when the client
goes, and the relay then closes its socket to the Object. The flag is
the default from compatibility date 2026-11-17. The static assets carry the page's script and stylesheet, and Cloudflare
serves them before the Worker runs. The Vite plugin of clojurific
exports the handler and the class from one bundle. `example/` has both
Vite configs.

## What is not here

- Files. `rframes.http` serves them with range requests, a Worker has
  its assets and R2 for that.
- A WebSocket straight from the browser. It would save the Worker in the
  middle, and it would bring back the 32 kB limit, since a WebSocket
  compresses with deflate only.
- Views that wait. A view runs while the Object renders every
  connection, so it reads what is in memory or in the Object's SQLite,
  which answers without a promise.

A page's path must not end in `/command`, that path is the command
endpoint.

## The example

`example/` is the counter and the notes of the repository's example,
on a Worker. Its state is one row in the Object's SQLite.

```bash
cd worker/example
npm ci
npm run dev       # builds both bundles, then http://127.0.0.1:8787
npm run deploy    # wrangler deploy
```

The page shows when its Object woke. Leave it alone for half a minute
and click again. The time changes and the count does not.

## Tests

```bash
cd worker && npm ci && npm test            # the module under Node, no network
cd worker/example && npm ci && npm test    # the example inside workerd, no network
```

The second one builds the example, starts `wrangler dev` and reads the
frame stream as a browser does. It takes half a minute, most of it
waiting for the Object to sleep.
