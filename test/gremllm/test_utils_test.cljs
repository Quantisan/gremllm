(ns gremllm.test-utils-test
  (:require [cljs.test :refer [deftest is]]
            [gremllm.test-utils :refer [with-console-silenced]]))

(deftest test-with-console-silenced-sync
  (let [original js/console.error
        calls (atom 0)]
    (set! js/console.error #(swap! calls inc))

    (with-console-silenced
      (js/console.error "silenced"))
    (is (= 0 @calls))

    (js/console.error "logged")
    (is (= 1 @calls))
    (set! js/console.error original)))

(deftest test-with-console-silenced-async
  (let [original js/console.error
        calls (atom 0)]
    (set! js/console.error #(swap! calls inc))

    (let [restore (with-console-silenced)]
      (js/console.error "silenced")
      (is (= 0 @calls))

      (restore)
      (js/console.error "logged")
      (is (= 1 @calls)))

    (set! js/console.error original)))
