(ns gremllm.renderer.ui.document.diffs)

;; Pending-diffs arrive as a single ordered batch per ACP tool-call response.
;; Within a batch, diff N's :old-text may reference content that only exists
;; after applying diffs 1..N-1 (sequential dependency). Independent diffs
;; reference the original document content directly. The composition pipeline
;; must handle both patterns: independent diffs produce separate :diff-block
;; segments; dependent overlapping diffs merge into a single :diff-block.

;; ---- Anchoring ----

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

(defn anchor
  "Locates each diff's old-text in the raw markdown content string.
   Returns diffs annotated with :anchor-status (:anchored/:unmatched/:ambiguous),
   :char-index, and :length for anchored diffs."
  [content diffs]
  (mapv #(anchor-diff content %) diffs))

;; ---- Resolution ----

(defn- apply-diff [text {:keys [old-text new-text]}]
  (let [idx (.indexOf text old-text)]
    (if (= -1 idx)
      text
      (str (subs text 0 idx) new-text (subs text (+ idx (count old-text)))))))

(defn- try-anchor [content diff]
  (first (anchor content [diff])))

(defn- evol-pos->orig-pos
  "Maps a position in evolved content back to the corresponding original position
   given the list of applied diffs (each with :orig-start :orig-len :new-len)."
  [evol-pos applied]
  (loop [remaining      applied
         running-offset 0]
    (if (empty? remaining)
      (- evol-pos running-offset)
      (let [{:keys [orig-start orig-len new-len]} (first remaining)
            evol-start (+ orig-start running-offset)
            evol-end   (+ evol-start new-len)]
        (if (<= evol-end evol-pos)
          (recur (rest remaining) (+ running-offset (- new-len orig-len)))
          (- evol-pos running-offset))))))

(defn- ranges-overlap? [a-start a-end b-start b-end]
  (and (< a-start b-end) (< b-start a-end)))

(defn- merge-diff-into-groups
  "Merges new-diffs covering [orig-start, orig-end) with any overlapping groups.
   Returns updated groups list with a single merged entry for the affected region."
  [groups orig-start orig-end new-diffs content]
  (let [{overlapping    true
         non-overlapping false}
        (group-by #(ranges-overlap? orig-start orig-end (:orig-start %) (:orig-end %)) groups)
        overlapping     (or overlapping [])
        non-overlapping (or non-overlapping [])

        all-starts   (conj (mapv :orig-start overlapping) orig-start)
        all-ends     (conj (mapv :orig-end overlapping) orig-end)
        all-diffs    (concat (mapcat :diffs overlapping) new-diffs)

        merged-start (apply min all-starts)
        merged-end   (apply max all-ends)
        merged-old   (subs content merged-start merged-end)
        merged-new   (reduce apply-diff merged-old all-diffs)]
    (conj (vec non-overlapping)
          {:orig-start    merged-start
           :orig-end      merged-end
           :diffs         all-diffs
           :old-text      merged-old
           :new-text      merged-new
           :char-index    merged-start
           :length        (- merged-end merged-start)
           :anchor-status :anchored})))

(defn- build-diff-groups
  "Processes diffs in order, classifying each as independent (anchors in original)
   or dependent (anchors in evolved content). Groups overlapping diffs into merged
   anchored-diff records suitable for compose-segments."
  [content diffs]
  (loop [remaining diffs
         groups    []
         evolved   content
         applied   []]
    (if (empty? remaining)
      groups
      (let [diff        (first remaining)
            orig-anchor (try-anchor content diff)]
        (if (= :anchored (:anchor-status orig-anchor))
          ;; Independent: anchors directly against original content
          (let [orig-start (:char-index orig-anchor)
                orig-end   (+ orig-start (:length orig-anchor))]
            (recur (rest remaining)
                   (merge-diff-into-groups groups orig-start orig-end [diff] content)
                   (apply-diff evolved diff)
                   (conj applied {:orig-start orig-start
                                  :orig-len   (:length orig-anchor)
                                  :new-len    (count (:new-text diff))})))
          ;; Dependent: try to anchor against evolved content and map back
          (let [evol-anchor (try-anchor evolved diff)]
            (if (= :anchored (:anchor-status evol-anchor))
              (let [evol-start (:char-index evol-anchor)
                    evol-end   (+ evol-start (:length evol-anchor))
                    orig-start (evol-pos->orig-pos evol-start applied)
                    orig-end   (evol-pos->orig-pos evol-end applied)]
                (recur (rest remaining)
                       (merge-diff-into-groups groups orig-start orig-end [diff] content)
                       (apply-diff evolved diff)
                       (conj applied {:orig-start orig-start
                                      :orig-len   (- orig-end orig-start)
                                      :new-len    (count (:new-text diff))})))
              ;; Unmatched in both original and evolved — skip
              (recur (rest remaining) groups evolved applied))))))))

;; ---- Segmentation ----

(defn compose-segments
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

(defn compose
  "Unified diff composition pipeline. Produces separate :diff-block segments for
   independent diffs and merges overlapping dependent diffs into single blocks."
  [content diffs]
  (compose-segments content (build-diff-groups content diffs)))
