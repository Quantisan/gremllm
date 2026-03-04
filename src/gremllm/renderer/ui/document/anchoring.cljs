(ns gremllm.renderer.ui.document.anchoring)

(defn- count-occurrences [content old-text]
  (let [step (.-length old-text)]
    (loop [start 0 n 0]
      (let [idx (.indexOf content old-text start)]
        (if (= idx -1)
          n
          (recur (+ idx step) (inc n)))))))

(defn- anchor-diff [content {:keys [old-text] :as diff}]
  (if (or (nil? old-text) (empty? old-text))
    (assoc diff :anchor-status :unmatched)
    (let [occurrences (count-occurrences content old-text)]
      (case occurrences
        0 (assoc diff :anchor-status :unmatched)
        1 (assoc diff :anchor-status :anchored
                      :char-index (.indexOf content old-text)
                      :length     (.-length old-text))
        (assoc diff :anchor-status :ambiguous :match-count occurrences)))))

(defn anchor-diffs
  "Locates each diff's old-text in the raw markdown content string.
   Returns diffs annotated with :anchor-status (:anchored/:unmatched/:ambiguous),
   :char-index, and :length for anchored diffs."
  [content diffs]
  (mapv #(anchor-diff content %) diffs))
