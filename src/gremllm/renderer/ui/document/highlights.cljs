(ns gremllm.renderer.ui.document.highlights)

(defn- span-at
  "Returns the [node start end] entry whose [start end) interval contains
   offset. Uses linear scan; spans are few per document."
  [spans offset]
  (some (fn [[_ s e :as span]]
          (when (and (<= s offset) (< offset e)) span))
        spans))

(defn- strip-newlines [s]
  (.replace s (js/RegExp. "\\n" "g") ""))

(defn- stripped->orig-idx
  "Translates a position in the newline-stripped view of text back to its
   index in the original text. Walks text, counting non-\\n chars until s
   equals stripped-pos."
  [text stripped-pos]
  (let [n (count text)]
    (loop [i 0, s 0]
      (if (or (= s stripped-pos) (>= i n))
        i
        (recur (inc i)
               (if (= (.charAt text i) "\n") s (inc s)))))))

(defn locate-range-in-flat-text
  "Finds the first occurrence of search-text within index's flat text.
   Newlines are normalized out of both sides before comparison: Selection
   .toString() inserts \\n at block boundaries, while flatten-article joins
   text nodes with no separator. Match offsets are translated back into the
   original flat text so spans line up with DOM text-node positions.
   index = {:text String :spans [[node-ref start-offset end-offset] ...]}
   Returns {:start-node :start-offset :end-node :end-offset} with offsets
   local to their node, or nil if not found / empty input."
  [{:keys [text spans]} search-text]
  (let [stripped-search (strip-newlines (or search-text ""))]
    (when (and (seq stripped-search) (seq spans))
      (let [stripped-text (strip-newlines text)
            s-idx         (.indexOf stripped-text stripped-search)]
        (when-not (neg? s-idx)
          (let [s-end              (+ s-idx (count stripped-search))
                idx                (stripped->orig-idx text s-idx)
                end-idx            (stripped->orig-idx text s-end)
                [start-node s-s _] (span-at spans idx)
                [end-node e-s _]   (span-at spans (dec end-idx))]
            (when (and start-node end-node)
              {:start-node   start-node
               :start-offset (- idx s-s)
               :end-node     end-node
               :end-offset   (- end-idx e-s)})))))))

(defn flatten-article
  "Walks article's descendant Text nodes in document order and returns
   {:text concatenated-text :spans [[node start end] ...]}. start/end are
   offsets into the concatenated text."
  [article]
  (let [walker (.createTreeWalker js/document
                                  article
                                  js/NodeFilter.SHOW_TEXT)]
    (loop [node   (.nextNode walker)
           pos    0
           parts  []
           spans  []]
      (if (nil? node)
        {:text  (.join (clj->js parts) "")
         :spans spans}
        (let [text (.-nodeValue node)
              len  (.-length text)]
          (recur (.nextNode walker)
                 (+ pos len)
                 (conj parts text)
                 (conj spans [node pos (+ pos len)])))))))

(def ^:private highlight-name "staged-excerpt")

(defn- make-range
  "Builds a DOM Range from a locate-range result and the containing document."
  [{:keys [start-node start-offset end-node end-offset]}]
  (let [r (.createRange js/document)]
    (.setStart r start-node start-offset)
    (.setEnd   r end-node   end-offset)
    r))

(defn- selection-texts [staged-selections]
  (keep #(get-in % [:selection :text]) staged-selections))

(defn sync!
  "Rebuilds the 'staged-excerpt' highlight registry entry from the given
   staged-selections against article's current text content. Safe to call
   on every render; missing matches are silently dropped."
  [article staged-selections]
  (let [index  (flatten-article article)
        ranges (->> (selection-texts staged-selections)
                    (keep #(locate-range-in-flat-text index %))
                    (mapv make-range))
        hl     (js/Highlight.)]
    (doseq [r ranges] (.add hl r))
    (.set js/CSS.highlights highlight-name hl)))

(defn clear!
  "Removes the 'staged-excerpt' highlight registry entry. Call on article
   unmount to avoid leaving ranges that point to detached nodes."
  []
  (.delete js/CSS.highlights highlight-name))
