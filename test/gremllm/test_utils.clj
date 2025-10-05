(ns gremllm.test-utils)

(defmacro with-console-silenced
  "Temporarily suppresses console.error while executing body.

  Synchronous usage:
    (with-console-silenced
      (do-something-that-logs))

  Asynchronous usage - returns a restore function to call after async operations:
    (let [restore (with-console-silenced)]
      (-> (async-operation)
          (.finally restore)))"
  [& body]
  (if (seq body)
    ;; Synchronous: silence, execute body, restore
    `(let [original# js/console.error]
       (try
         (set! js/console.error (constantly nil))
         ~@body
         (finally
           (set! js/console.error original#))))
    ;; Asynchronous: silence now, return restore function
    `(let [original# js/console.error]
       (set! js/console.error (constantly nil))
       (fn [] (set! js/console.error original#)))))
