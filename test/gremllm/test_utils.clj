(ns gremllm.test-utils)

(defmacro with-console-silenced
  "Temporarily suppresses console.error while executing body."
  [& body]
  `(let [original# js/console.error]
     (try
       (set! js/console.error (constantly nil))
       ~@body
       (finally
         (set! js/console.error original#)))))
