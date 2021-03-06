(ns missionary.core
  (:require [missionary.impl :as i]
            [cloroutine.core :refer [cr] :include-macros true])
  #?(:cljs (:require-macros [missionary.core :refer [sp ap]])))


(def
  ^{:static true
    :doc "A `java.util.concurrent.Executor` optimized for blocking evaluation."}
  blk i/blk)


(def
  ^{:static true
    :doc "A `java.util.concurrent.Executor` optimized for non-blocking evaluation."}
  cpu i/cpu)


(defn
  ^{:static true
    :arglists '([executor thunk])
    :doc "
Same as `via`, except the expression to evaluate is provided as a zero-arity function on second argument.

Not supported on clojurescript.

```clojure
(? (via-call blk read-line))
;; reads a line from stdin and returns it
```
"} via-call [e t] (fn [s f] (i/thunk e t s f)))


(defmacro
  ^{:arglists '([executor & body])
    :doc "
Returns a task evaluating body (in an implicit `do`) on given `java.util.concurrent.Executor` and completing with its result.

Cancellation interrupts the evaluating thread.

Not supported on clojurescript.

Example :
```clojure

```
"} via [exec & body] `(via-call ~exec #(do ~@body)))


(defn
  ^{:static true
    :arglists '([duration] [duration value])
    :doc "
Returns a task completing with given value (nil if not provided) after given duration (in milliseconds).

Cancelling a sleep task makes it fail immediately.

Example :
```clojure
(? (sleep 1000 42))
#_=> 42               ;; 1 second later
```
"} sleep
  ([d] (sleep d nil))
  ([d x] (fn [s f] (i/sleep d x s f))))


(defn
  ^{:static true
    :arglists '([f & tasks])
    :doc "
Returns a task running given `tasks` concurrently.

If every task succeeds, `join` completes with the result of applying `f` to these results.

If any task fails, others are cancelled then `join` fails with this error.

Cancelling propagates to children tasks.

Example :
```clojure
(? (join vector (sleep 1000 1) (sleep 1000 2)))
#_=> [1 2]            ;; 1 second later
```
"} join
  ([c] (fn [s _] (s (c)) i/nop))
  ([c & ts] (fn [s f] (i/race-join false c ts s f))))


(defn ^{:static true :no-doc true} race-failure [& errors]
  (ex-info "Race failure." {::errors errors}))


(defn
  ^{:static true
    :arglists '([& tasks])
    :doc "
Returns a task running given `tasks` concurrently.

If any task succeeds, others are cancelled then `race` completes with this result.

If every task fails, `race` fails.

Cancelling propagates to children tasks.

Example :
```clojure
(? (race (sleep 1000 1) (sleep 2000 2)))
#_=> 1                 ;; 1 second later
```
"} race
  ([] (fn [_ f] (f (race-failure)) i/nop))
  ([& ts] (fn [s f] (i/race-join true race-failure ts s f))))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Returns a task always succeeding with result of given `task` wrapped in a zero-argument function returning result if successful or throwing exception if failed.
"} attempt [task]
  (fn [s _] (task (fn [x] (s #(-> x))) (fn [e] (s #(throw e))))))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Returns a task running given `task` completing with a zero-argument function and completing with the result of this function call.
"} absolve [task]
  (fn [s f] (task (i/absolver s f) f)))


(defn
  ^{:static true
    :arglists '([delay task])
    :doc "
Returns a task running given `task` and cancelling it if not completed within given `delay` (in milliseconds).

```clojure
(? (timeout 100 (sleep (rand-int 200))))
#_=> nil       ;; or exception, after 100 milliseconds
```
"} timeout [delay task]
  (->> task
       (attempt)
       (race (sleep delay #(throw (ex-info "Task timed out." {::delay delay, ::task task}))))
       (absolve)))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Throws an exception if current computation is required to terminate, otherwise returns nil.

Inside a process block, checks process cancellation status.

Outside of process blocks, fallbacks to thread interruption status if host platform supports it.
"} ! [] (i/fiber-poll))


(defn
  ^{:arglists '([task])
    :doc "
Runs given task, waits for completion and returns result (or throws, if task fails).

Inside a process block, parks the process.

Outside of process blocks, fallbacks to thread blocking if host platform supports it.
"} ? [t] (i/fiber-task t))


(defn
  ^{:arglists '([flow])
    :doc "
In an ambiguous process block, runs given `flow` non-preemptively (aka concat), forking process for each emitted value.

Example :
```clojure
(? (aggregate conj (ap (inc (?? (enumerate [1 2 3]))))))
#_=> [2 3 4]
```
"} ?? [f] (i/fiber-flow-concat f))


(defn
  ^{:arglists '([flow])
    :doc "
In an ambiguous process block, runs given `flow` preemptively (aka switch), forking process for each emitted value. Forked process is cancelled if `flow` emits another value before it terminates.

Example :
```clojure
(defn debounce [delay flow]
  (ap (let [x (?! flow)]
    (try (? (sleep delay x))
         (catch Exception _ (?? none))))))

(? (->> (ap (let [n (?? (enumerate [24 79 67 34 18 9 99 37]))]
              (? (sleep n n))))
        (debounce 50)
        (aggregate conj)))
```
#_=> [24 79 9 37]
"} ?! [f] (i/fiber-flow-switch f))


(defn
  ^{:arglists '([flow])
    :doc "
In an ambiguous process block, runs given `flow` and concurrently forks current process for each value produced by the flow. Values emitted by forked processes are gathered and emitted as soon as available.

Example :
```clojure
(? (->> (m/ap
          (let [x (m/?= (m/enumerate [19 57 28 6 87]))]
            (m/? (m/sleep x x))))
        (aggregate conj)))

#_=> [6 19 28 57 87]
```
"} ?= [f] (i/fiber-flow-gather f))


(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a task evaluating `body` (in an implicit `do`). Body evaluation can be parked with `?`.

Cancelling an `sp` task triggers cancellation of the task it's currently running, along with all tasks subsequently run.
"} sp [& body]
  `(partial (cr {? i/fiber-unpark} ~@body) i/sp))


(defmacro
  ^{:arglists '([& body])
    :doc "
Returns a flow evaluating `body` (in an implicit `do`) and producing values of each subsequent fork. Body evaluation can be parked with `?` and forked with `??` and `?!`.

Cancelling an `ap` flow triggers cancellation of the task/flow it's currently running, along with all tasks/flows subsequently run.
"} ap [& body]
  `(partial (cr {?  i/fiber-unpark
                 ?? i/fiber-unpark
                 ?! i/fiber-unpark
                 ?= i/fiber-unpark}
              ~@body) i/ap))


(defn
  ^{:static true
    :arglists '([task])
    :doc "
Inhibits cancellation signal of given `task`.
"} compel [task]
  (fn [s f] (task s f) i/nop))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of dataflow variable (aka single-assignment).

A dataflow variable is a function implementing `assign` on 1-arity and `deref` on 2-arity (as task). `assign` immediately binds the variable to given value if not already bound and returns bound value. `deref` is a task completing with the value bound to the variable as soon as it's available.

Cancelling a `deref` task makes it fail immediately.
```
"} dfv [] (i/dataflow))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of mailbox.

A mailbox is a function implementing `post` on 1-arity and `fetch` on 2-arity (as task). `post` immediately pushes given value to mailbox and returns nil. `fetch` is a task pulling a value from mailbox as soon as it's non-empty and completing with this value.

Cancelling a `fetch` task makes it fail immediately.

Example : an actor is a mailbox associated with a process consuming messages.
```clojure
(defn crash [^Throwable e]                                ;; let it crash philosophy
  (.printStackTrace e)
  (System/exit -1))

(defn actor
  ([init] (actor init crash))
  ([init fail]
   (let [self (mbx)]
     ((sp
        (loop [b init]
          (recur (b self (? self)))))
       nil fail)
     self)))

(def counter
  (actor
    ((fn beh [n]
       (fn [self cust]
         (cust n)
         (beh (inc n)))) 0)))

(counter prn)                                             ;; prints 0
(counter prn)                                             ;; prints 1
(counter prn)                                             ;; prints 2
```
"} mbx [] (i/mailbox))


(defn
  ^{:static true
    :arglists '([])
    :doc "
Creates an instance of synchronous rendez-vous.

A synchronous rendez-vous is a function implementing `give` on its 1-arity and `take` on its 2-arity (as task). `give` takes a value to be transferred and returns a task completing with nil as soon as a taker is available. `take` is a task completing with transferred value as soon as a giver is available.

Cancelling `give` and `take` tasks makes them fail immediately.

Example : producer / consumer stream communication
```clojure
(defn reducer [rf i take]
  (sp
    (loop [r i]
      (let [x (? take)]
        (if (identical? x take)
          r (recur (rf r x)))))))

(defn iterator [give xs]
  (sp
    (loop [xs (seq xs)]
      (if-some [[x & xs] xs]
        (do (? (give x))
            (recur xs))
        (? (give give))))))

(def stream (rdv))

(? (join {} (iterator stream (range 100)) (reducer + 0 stream)))      ;; returns 4950
```
"} rdv [] (i/rendezvous))


(defn
  ^{:static true
    :arglists '([] [n])
    :doc "
Creates a semaphore initialized with n tokens (1 if not provided, aka mutex).

A semaphore is a function implementing `release` on 0-arity and `acquire` on 2-arity (as task). `release` immediately makes a token available and returns nil. `acquire` is a task completing with nil as soon as a token is available.

Cancelling an `acquire` task makes it fail immediately.

Example : dining philosophers
```clojure
(defn phil [name f1 f2]
  (sp
    (while true
      (prn name :thinking)
      (? (sleep 500))
      (holding f1
        (holding f2
          (prn name :eating)
          (? (sleep 600)))))))

(def forks (vec (repeatedly 5 sem)))

(? (timeout 10000
     (join vector
       (phil \"descartes\" (forks 0) (forks 1))
       (phil \"hume\"      (forks 1) (forks 2))
       (phil \"plato\"     (forks 2) (forks 3))
       (phil \"nietzsche\" (forks 3) (forks 4))
       (phil \"kant\"      (forks 0) (forks 4)))))
```
"} sem
  ([] (sem 1))
  ([n] (i/semaphore n)))


(defmacro
  ^{:arglists '([semaphore & body])
    :doc "
`acquire`s given `semaphore` and evaluates `body` (in an implicit `do`), ensuring `semaphore` is `release`d after evaluation.
"} holding [lock & body]
  `(let [l# ~lock] (? l#) (try ~@body (finally (l#)))))


(def never
  ^{:static true
    :doc "
A task never succeeding. Cancelling makes it fail immediately."}
  (fn [_ f] (i/never f)))


(def
  ^{:static true
    :doc "
The empty flow. Doesn't produce any value and terminates immediately. Cancelling has no effect.

Example :
```clojure
(? (aggregate conj none))
#_=> []
```
"} none (fn [_ t] (t) i/nop))


(defn
  ^{:static true
    :arglists '([collection])
    :doc "
Returns a discrete flow producing values from given `collection`. Cancelling before having reached the end makes the flow fail immediately.
"} enumerate [coll]
  (fn [n t] (i/enumerate coll n t)))


(defn
  ^{:static true
    :arglists '([rf flow] [rf init flow])
    :doc "
Returns a task reducing values produced by given discrete `flow` with `rf`, starting with `init` (or, if not provided, the result of calling `rf` with no argument).

Cancelling propagates to upstream flow. Early termination by `rf` (via `reduced` or throwing) cancels upstream flow.

Example :
```clojure
(? (aggregate + (enumerate (range 10))))
#_=> 45
```
"} aggregate
  ([rf flow] (fn [s f] (i/aggregate rf (rf) flow s f)))
  ([rf i flow] (fn [s f] (i/aggregate rf i flow s f))))


(defn
  ^{:static true
    :arglists '([reference])
    :doc "
Returns a continuous flow producing successive values of given `reference` until cancelled. Given reference must support `add-watch`, `remove-watch` and `deref`. Oldest values are discarded on overflow.
"} watch [r] (fn [n t] (i/watch r n t)))


(defn
  ^{:static true
    :arglists '([subject])
    :doc "
Returns a discrete flow observing values produced by a non-backpressured subject until cancelled. `subject` must be a function taking a 1-arity `event` function and returning a 0-arity `cleanup` function.

`subject` function is called on initialization. `cleanup` function is called on cancellation. `event` function may be called at any time, it throws an exception on overflow and becomes a no-op after cancellation.
"} observe [s] (fn [n t] (i/observe s n t)))


(defn
  ^{:static true
    :arglists '([xf flow])
    :doc "
Returns a discrete flow running given discrete `flow` and transforming values with given transducer `xf`.

Cancelling propagates to upstream flow. Early termination by `xf` (via `reduced` or throwing) cancels upstream flow.

Example :
```clojure
(? (->> (enumerate (range 10))
        (transform (comp (filter odd?) (mapcat range) (partition-all 4)))
        (aggregate conj)))
#_=> [[0 0 1 2] [0 1 2 3] [4 0 1 2] [3 4 5 6] [0 1 2 3] [4 5 6 7] [8]]
```
"} transform [x f] (fn [n t] (i/transform x f n t)))


(defn
  ^{:static true
    :arglists '([rf flow] [rf init flow])
    :doc "
Returns a discrete flow running given discrete `flow` and emitting given `init` value (or, if not provided, the result of calling `rf` with no argument) followed by successive reductions (by rf) of upstream values with previously emitted value.

Cancelling propagates to upstream flow. Early termination by `rf` (via `reduced` or throwing) cancels upstream flow.

Example :
```clojure
(? (->> [1 2 3 4 5]
        (enumerate)
        (integrate +)
        (aggregate conj)))
#_=> [0 1 3 6 10 15]
```
"} integrate
  ([rf f] (fn [n t] (i/integrate rf (rf) f n t)))
  ([rf i f] (fn [n t] (i/integrate rf i f n t))))


(defn
  ^{:static true
    :arglists '([flow])
    :doc "
Returns a `org.reactivestreams.Publisher` running given discrete `flow` on each subscription.
"} publisher [flow] (i/publisher flow))


(defn
  ^{:static true
    :arglists '([pub])
    :doc "
Returns a discrete flow subscribing to given `org.reactivestreams.Publisher`.
"} subscribe [pub] (fn [n t] (i/subscribe pub n t)))


(defn
  ^{:static true
    :arglists '([rf flow])
    :doc "
Returns a continuous flow producing values emitted by given discrete `flow`, relieving backpressure. When upstream is faster than downstream, overflowed values are successively reduced with given function `rf`.

Cancelling propagates to upstream. If `rf` throws, upstream `flow` is cancelled.

Example :
```clojure
;; Delays each `input` value by `delay` milliseconds
(defn delay-each [delay input]
  (ap (? (sleep delay (?? input)))))

(? (->> (ap (let [n (?? (enumerate [24 79 67 34 18 9 99 37]))]
              (? (sleep n n))))
        (relieve +)
        (delay-each 80)
        (aggregate conj)))
#_=> [24 79 67 61 99 37]
```
"} relieve [rf f] (fn [n t] (i/relieve rf f n t)))


(defn
  ^{:static true
    :arglists '([capacity flow])
    :doc "
Returns a discrete flow producing values emitted by given discrete `flow`, accumulating upstream overflow up to `capacity` items.
"} buffer [c f]
  (assert (pos? c) "Non-positive buffer capacity.")
  (fn [n t] (i/buffer c f n t)))


(defn
  ^{:static true
    :arglists '([f & flows])
    :doc "
Returns a continuous flow running given continuous `flows` in parallel and combining latest value of each flow with given function `f`.

```clojure
(defn sleep-emit [delays]
  (ap (let [n (?? (enumerate delays))]
        (? (sleep n n)))))

(defn delay-each [delay input]
  (ap (? (sleep delay (?? input)))))

(? (->> (latest vector
                (sleep-emit [24 79 67 34])
                (sleep-emit [86 12 37 93]))
        (delay-each 50)
        (aggregate conj)))

#_=> [[24 86] [24 12] [79 37] [67 37] [34 93]]
```
"} latest
  ([f] (ap (f)))
  ([f & fs] (fn [n t] (i/latest f fs n t))))


(defn
  ^{:static true
    :arglists '([f sampled sampler])
    :doc "
Returns a discrete flow running given `sampler` discrete flow and `sampled` continuous flow in parallel. For each `sampler` value, emits the result of function `f` called with current values of `sampled` and `sampler`.

Cancellation propagates to both flows. When `sampler` terminates, `sampled` is cancelled. A failure in any of both flows, or `f` throwing an exception, or trying to pull a value before first value of `sampled` will cancel the flow and propagate the error.

Example :
```clojure
(defn sleep-emit [delays]
  (ap (let [n (?? (enumerate delays))]
        (? (sleep n n)))))

(defn delay-each [delay input]
  (ap (? (sleep delay (?? input)))))

(? (->> (sample vector
                (sleep-emit [24 79 67 34])
                (sleep-emit [86 12 37 93]))
        (delay-each 50)
        (aggregate conj)))

#_=> [[24 86] [24 12] [79 37] [67 93]]
```
"} sample [f sd sr] (fn [n t] (i/sample f sd sr n t)))


(defn
  ^{:static true
    :arglists '([& flows])
    :doc "
Returns a discrete flow running given discrete `flows` in parallel and emitting upstream values unchanged, as soon as they're available, until every upstream flow is terminated.

Cancelling propagates to every upstream flow. If any upstream flow fails, the flow is cancelled.

Example :
```clojure
(? (->> (gather (enumerate [1 2 3])
                (enumerate [:a :b :c]))
        (aggregate conj)))
#_=> [1 :a 2 :b 3 :c]
```
"} gather
  ([] none)
  ([f] f)
  ([f & fs] (ap (?? (?= (enumerate (cons f fs)))))))


(defn
  ^{:static true
    :arglists '([f & flows])
    :doc "
Returns a discrete flow running given discrete `flows` is parallel and emitting the result of applying `f` to the set of first values emitted by each upstream flow, followed by the result of applying `f` to the set of second values and so on, until any upstream flow terminates, at which point the flow will be cancelled.

Cancelling propagates to every upstream flow. If any upstream flow fails or if `f` throws, the flow is cancelled.

Example :
```clojure
(m/? (->> (m/zip vector
                 (m/enumerate [1 2 3])
                 (m/enumerate [:a :b :c]))
          (m/aggregate conj)))
#_=> [[1 :a] [2 :b] [3 :c]]
```
"} zip [c f & fs] (fn [n t] (i/zip c (cons f fs) n t)))


(defn
  ^{:static true
    :arglists '([boot])
    :doc "
Returns a task running given zero-argument function in a fresh reactor context.

A reactor collects events from different sources, dispatches events to subscribers.

The reactor terminates when its last node terminates. The task succeeds with the result of the boot function if all nodes completed successfully, otherwise the first encountered failure is propagated. When the task is cancelled, or when a node fails, each remaining node and subsequently spawned ones are cancelled.
"} reactor-call [i] (fn [s f] (i/dag i s f)))


(defmacro
  ^{:arglists '([& body])
    :doc "
Calls `broker-call` with a function evaluating given `body` in an implicit `do`.
"} reactor [& body] `(reactor-call (fn [] ~@body)))


(defn
  ^{:static true
    :arglists '([flow])
    :doc "
Must be run in a broker context.
Spawns a discrete node from given flow.
"} stream! [f] (i/pub f true))


(defn
  ^{:static true
    :arglists '([flow])
    :doc "
Must be run in a broker context.
Spawns a continuous node from given flow.
"} signal! [f] (i/pub f false))