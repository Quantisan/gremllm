(ns gremllm.renderer.ui.document.anchoring)

(defn- find-occurrences
  "Returns {:count n :first-index idx} for old-text in content."
  [content old-text]
  (let [step (.-length old-text)]
    (loop [start 0 n 0 first-idx -1]
      (let [idx (.indexOf content old-text start)]
        (if (= idx -1)
          {:count n :first-index (when (pos? n) first-idx)}
          (recur (+ idx step) (inc n) (if (zero? n) idx first-idx)))))))

(defn- anchor-diff [content {:keys [old-text] :as diff}]
  (if (or (nil? old-text) (empty? old-text))
    (assoc diff :anchor-status :unmatched)
    (let [{:keys [count first-index]} (find-occurrences content old-text)]
      (case count
        0 (assoc diff :anchor-status :unmatched)
        1 (assoc diff :anchor-status :anchored
                      :char-index first-index
                      :length     (.-length old-text))
        (assoc diff :anchor-status :ambiguous :match-count count)))))

(defn anchor-diffs
  "Locates each diff's old-text in the raw markdown content string.
   Returns diffs annotated with :anchor-status (:anchored/:unmatched/:ambiguous),
   :char-index, and :length for anchored diffs."
  [content diffs]
  (mapv #(anchor-diff content %) diffs))
