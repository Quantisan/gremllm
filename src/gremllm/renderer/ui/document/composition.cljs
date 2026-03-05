(ns gremllm.renderer.ui.document.composition)

(defn compose-diff-segments
  "Splits content string into ordered segments for diff rendering.
   Anchored diffs are inserted as :diff-block segments; surrounding text
   becomes :text segments. Unmatched and ambiguous diffs are silently ignored."
  [content anchored-diffs]
  (let [sorted (->> anchored-diffs
                    (filter #(= :anchored (:anchor-status %)))
                    (sort-by :char-index))]
    (loop [pos      0
           diffs    sorted
           segments []]
      (if (empty? diffs)
        (if (< pos (count content))
          (conj segments {:type :text :content (subs content pos)})
          segments)
        (let [{:keys [char-index length old-text new-text]} (first diffs)
              ;; WARN: Overlapping diffs will crash the renderer. if two anchored diffs overlap (LLM
              ;; produces edits targeting adjacent text), subs gets called with pos > char-index and
              ;; throws.
              before (subs content pos char-index)]
          (recur (+ char-index length)
                 (rest diffs)
                 (cond-> segments
                   (seq before) (conj {:type :text :content before})
                   true         (conj {:type :diff-block :old-text old-text :new-text new-text}))))))))
