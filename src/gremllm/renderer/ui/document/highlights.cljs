(ns gremllm.renderer.ui.document.highlights)

(defn- span-at
  "Returns the [node start end] entry whose [start end) interval contains
   offset. Uses linear scan; spans are few per document."
  [spans offset]
  (some (fn [[_ s e :as span]]
          (when (and (<= s offset) (< offset e)) span))
        spans))

(defn locate-range-in-flat-text
  "Finds the first occurrence of search-text within index's flat text.
   index = {:text String :spans [[node-ref start-offset end-offset] ...]}
   Returns {:start-node :start-offset :end-node :end-offset} with offsets
   local to their node, or nil if not found / empty input."
  [{:keys [text spans]} search-text]
  (when (and (seq search-text) (seq spans))
    (let [idx (.indexOf text search-text)]
      (when (not (neg? idx))
        (let [end-idx            (+ idx (count search-text))
              ;; end-idx may equal text length (exclusive); for span lookup
              ;; treat the last char position as (end-idx - 1).
              [start-node s-s _] (span-at spans idx)
              [end-node e-s _]   (span-at spans (dec end-idx))]
          (when (and start-node end-node)
            {:start-node   start-node
             :start-offset (- idx s-s)
             :end-node     end-node
             :end-offset   (- end-idx e-s)}))))))
