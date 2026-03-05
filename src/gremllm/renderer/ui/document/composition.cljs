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
        (let [{:keys [char-index length old-text new-text anchor-status]} (first diffs)]
          (recur (max pos (+ char-index length))
                 (rest diffs)
                 (cond-> segments
                   (< pos char-index)
                   (conj {:type :text :content (subs content pos char-index)})

                   true
                   (conj {:type :diff-block :old-text old-text :new-text new-text :anchor-status anchor-status}))))))))

(defn- apply-diff [text {:keys [old-text new-text]}]
  (let [idx (.indexOf text old-text)]
    (if (= -1 idx)
      text
      (str (subs text 0 idx) new-text (subs text (+ idx (count old-text)))))))

(defn- common-prefix-len [a b]
  (let [limit (min (count a) (count b))]
    (loop [i 0]
      (if (or (= i limit) (not= (.charAt a i) (.charAt b i)))
        i
        (recur (inc i))))))

(defn- common-suffix-len [a b prefix-len]
  (let [limit (- (min (count a) (count b)) prefix-len)]
    (loop [i 0]
      (if (or (= i limit)
              (not= (.charAt a (- (count a) i 1))
                    (.charAt b (- (count b) i 1))))
        i
        (recur (inc i))))))

(defn compose-sequential-diffs
  "Applies diffs in sequence against evolving content, then produces renderable
   segments by comparing original to final via prefix/suffix analysis."
  [content diffs]
  (let [final     (reduce apply-diff content diffs)
        prefix    (common-prefix-len content final)
        suffix    (common-suffix-len content final prefix)
        orig-end  (- (count content) suffix)
        final-end (- (count final) suffix)]
    (if (= prefix orig-end)
      [{:type :text :content content}]
      (compose-diff-segments content
        [{:type        "diff"
          :old-text    (subs content prefix orig-end)
          :new-text    (subs final prefix final-end)
          :anchor-status :anchored
          :char-index  prefix
          :length      (- orig-end prefix)}]))))
