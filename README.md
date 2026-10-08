# rframes

Server-driven UI for Clojure with hiccup on the wire instead of HTML.

Every view is a pure function of one atom to hiccup. The server pushes the
whole view as a transit frame over an SSE connection it holds open, and
[Replicant](https://replicant.fun) diffs the frame into the DOM. Writes are
command maps the browser POSTs to one endpoint. A command changes the atom,
a watch pushes a new frame to every open tab, and that is the whole loop.

It is the shape of [Datastar](https://data-star.dev) with data in both
directions. There are no HTML fragments to morph and no patch protocol to
maintain. One frame is the whole page, and gzip over the stream makes a
frame that resembles the one before it cheap. Event handlers are data too,
so a view is a value a test can `get-in`.

The server runs on babashka, or on the JVM with http-kit. The browser half
is ClojureScript, compiled by shadow-cljs. The code came out of video-agent, where
it was a port of the UI of lith, a chat UI for a coding agent.

## The loop

```
        +---------------------- server ----------------------+
        |  db (atom) --watch--> rframes.sse/schedule-refresh!   |
        |      ^                        | at most 10 per s   |
        |      |                        v                    |
        |  command fns            view fn (w -> hiccup)      |
        +------+------------------------+--------------------+
               | POST /command          | SSE "frame" (transit)
        +------+------------------------v-------- browser ---+
        |  [:data/command ...]    store :server/frame        |
        |      ^                        | store watch        |
        |      |                        v                    |
        |  action vectors <--DOM-- Replicant render          |
        +----------------------------------------------------+
```

## Install

The repository has two modules, as
[parts-cljs](https://github.com/simplemono/parts-cljs) has. The server
module goes into `bb.edn` or `deps.edn`:

```clojure
io.github.simplemono/rframes$server
{:git/url "https://github.com/simplemono/rframes.git"
 :git/sha "<sha>"
 :deps/root "server"}
```

The client module goes into the `deps.edn` of the shadow-cljs build. It
brings parts-cljs, Replicant and transit-cljs along:

```clojure
io.github.simplemono/rframes$client
{:git/url "https://github.com/simplemono/rframes.git"
 :git/sha "<sha>"
 :deps/root "client"}
```

While working on both at once, `{:local/root "../rframes/server"}` and
`{:local/root "../rframes/client"}` take the checkout instead.

## The server

System wiring is data. Every namespace of an app contributes a register, a
sequence of entries, and the router consults the register per request, so
a redefined var is live at once. Four kinds of entries matter here:

```clojure
(def register
  [{:ring/route [:get "/files/*"]      ; a route, the handler is w -> w
    :ring/handler #'files-handler}
   {:replicant/shim "/"                ; GET: the static shell of the page
    :head "<link rel=\"stylesheet\" href=\"/app.css\"><script defer src=\"/js/main.js\"></script>"}
   {:replicant/render "/"              ; POST: the frame stream of the page
    :render/fn #'page-view}
   {:command/kind :note/add            ; a command the browser may issue
    :command/fn #'add-note}])
```

`rframes.replicant/expand` turns the shim and render entries into routes,
`rframes.command/register` is the `/command` route, and `rframes.http/ring-handler`
serves the lot:

```clojure
(defn get-register []
  (replicant/expand (concat register command/register)))

(defn -main [& _]
  (sse/watch! db)
  (server/run-server (http/ring-handler get-register) {:port 8080})
  @(promise))
```

`rframes.sse/watch!` is the line that makes mutating enough. After it, a command
swaps the atom and stops. I used to write `swap!` followed by a refresh call
and forget the second half about once a week; the watch makes that bug
unwritable. The refresh is coalesced to at most ten per second, so a source
that changes the atom fifty times in a burst re-renders every connection
once.

A handler is a function `w -> w` of the world: `:ring/request` in,
`:ring/response` out. A view function gets the world of its connection, with
`:conn-id` and `:tabid` (the id the browser sends when it opens the stream)
for state per tab, and derefs the atom once:

```clojure
(defn page-view [{:keys [tabid]}]
  (let [{:keys [notes]} @db]
    [:main
     [:ui/textarea {:ui/store-key :draft}]
     [:button {:on {:click [[:data/command
                             {:command/kind :note/add
                              :command/data {:text [:store/deref :draft]}}
                             {:on-success [[:store/dissoc :draft]]}]]}}
      "Add"]
     [:ul (for [[i note] (map-indexed vector notes)]
            [:li {:replicant/key i} note])]]))
```

A command function reads `(command/command-data w)` and answers with
`command/accepted` or `(command/rejected w :reason)`. The answer carries no
state. What the command caused arrives as the next frame, in every tab, and
a change looks the same whether it came from this tab, another one or a
background thread.

```clojure
(defn add-note [w]
  (let [text (:text (command/command-data w))]
    (if (str/blank? text)
      (command/rejected w :empty-note)
      (do (swap! db update :notes conj text)
          (command/accepted w)))))
```

`rframes.http` also has what a page needs besides. `(http/resources "/js/*"
"public/js")` is an entry that serves classpath resources, `http/file-response`
serves a file with range requests, which `<video>` needs to seek, and
`http/resolve-file` keeps a URL path below its root.

## The browser

The `:init-fn` of the bundle starts the runtime with the client register of
the app:

```clojure
(ns my.client
  (:require [rframes.client :as rframes]
            [my.components :as components]))

(defn init! []
  (rframes/init! {:register components/register}))
```

The runtime mounts on the root node of the shim, opens the stream with a
POST to the page's own path and puts every frame under `:server/frame` of
the one client store. Every store change re-renders. The connection retries
forever with a capped backoff, reconnects when the browser comes back
online, and a hidden tab closes its stream and reopens it when it becomes
visible. A new connection gets a full frame, so there is no resume
protocol.

A frame crosses a process boundary, so it is data. Three things follow.

**Event handlers are action vectors.** The client register interprets them,
and the vocabulary of parts-cljs is built in: `[:store/assoc k v]`,
`[:store/dissoc k]`, `[:data/command {...} {:on-success [...]}]`,
`[:js/confirm "Sure?"]` as a gate in front of the rest, `:event/target.value`
for the value of the input that fired. `rframes.client.ui` adds
`[:store/deref k]`, which becomes the store's value of `k` at the moment of
the click.

**What the browser must own is an alias.** The server writes
`[:ui/player {:ui/src "/x.html"}]` and decides when it appears. The client
decides what it renders, with real functions, lifecycle hooks and DOM
handles that never cross the wire. An alias is a function of attrs and
children, registered as `{:ui.alias/kind :ui/player :ui.alias/fn player}`.
Before every render the runtime puts the client state into the attrs of
every `:ui/*` node as `:ui/state`. That is how a store-backed input survives
a frame that arrives while the user types: its value comes from the store,
and the frame paints the same characters back. `:ui/textarea` with a
`:ui/store-key` is built in.

**Client-side effects are action handlers.**
`{:ui.action/kind :player/reload :ui.action/handler (fn [{:keys [store action]}] ...)}`
in the register handles `[:player/reload]`. Code outside a DOM event, a
window listener say, dispatches with `rframes.client.state/dispatch!`.

One more built-in: an element with a `data-stick-to-end` attribute stays
scrolled to its end across renders as long as the user had not scrolled
away from it, which is what a chat or a log wants.

## Rules for a frame

- Fragments are seqs, never vectors of vectors. `(for ...)` is right,
  `(mapv ...)` is wrong: transit writes a seq as a list and a vector as an
  array, and Replicant renders a vector child that does not start with a
  keyword as text.
- No functions in a frame. The encoder drops the frame with a line on
  stderr, and the page stops updating until the next refresh. A test that
  walks the view for functions and round-trips it through `rframes.transit`
  catches this before a browser does.
- Nothing in a view may block. The view runs on the refresh thread and the
  render of a connection runs under a lock.
- `:replicant/key` wherever recreating a node is destructive: an iframe, a
  player, a list that reorders.

## The example

`example/` is a counter and a list of notes, shared by every tab that has
the page open.

```bash
cd example
clojure -M -m shadow.cljs.devtools.cli release browser   # the bundle, needs a JVM
bb -m example.main                                       # http://localhost:8080
```

## Configuration

`RFRAMES_SSE_GZIP=0` turns the gzip of the stream off, for a reverse proxy that
buffers an encoded response until its end, which never comes on a view
connection.

## Tests

```bash
bb test                        # the server tests on babashka
cd server && clojure -M:test   # the same on the JVM
```
