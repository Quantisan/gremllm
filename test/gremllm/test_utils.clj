(ns gremllm.test-utils)

(defmacro with-console-silenced
  "Temporarily suppresses console.error.

  Zero-arity (async): Returns a restore function to call later.
    (let [restore (with-console-silenced)]
      (-> (async-operation)
          (.finally restore)))

  With body (sync): Executes body with console silenced, then restores.
    (with-console-silenced
      (do-something-that-logs))"
  ([]
   ;; Async: silence now, return restore function
   `(let [original# js/console.error]
      (set! js/console.error (constantly nil))
      (fn [] (set! js/console.error original#))))
  ([& body]
   ;; Sync: silence, execute body, restore
   `(let [original# js/console.error]
      (try
        (set! js/console.error (constantly nil))
        ~@body
        (finally
          (set! js/console.error original#))))))
