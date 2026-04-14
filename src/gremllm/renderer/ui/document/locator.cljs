(ns gremllm.renderer.ui.document.locator
  (:require ["/js/markdown" :as markdown-js]))

(def ^:private block-open->kind
  {"heading_open"    :heading
   "paragraph_open"  :paragraph
   "list_item_open"  :list-item
   "blockquote_open" :blockquote
   "fence"           :code-block
   "code_block"      :code-block
   "table_open"      :table})

(def ^:private block-selector "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,table")

(defn- token-kind [^js token]
  (get block-open->kind (.-type token)))

(defn- inline-token? [^js token]
  (= "inline" (.-type token)))

(defn- token-map [^js token]
  (some-> (.-map token) js->clj))

(defn- line-span [m]
  (when-let [[start end] m]
    {:start-line (inc start)
     :end-line   end}))

(defn- token-map->line-span [^js token]
  (when-let [m (token-map token)]
    (let [[start end] m]
      {:start-line (inc start)
       :end-line   end})))

(defn- inline-text [^js token]
  (->> (array-seq (or (.-children token) #js []))
       (map (fn [^js child]
              (case (.-type child)
                "softbreak" " "
                (.-content child))))
       (apply str)))

(defn- within-map? [outer inner]
  (let [[outer-start outer-end] outer
        [inner-start inner-end] inner]
    (and outer-start outer-end inner-start inner-end
         (<= outer-start inner-start)
         (<= inner-end outer-end))))

(defn- matching-inline-token [tokens idx ^js token]
  (let [outer-map (token-map token)]
    (some->> (drop (inc idx) tokens)
             (filter inline-token?)
             (filter #(within-map? outer-map (token-map %)))
             first)))

(defn- token-line-span [tokens idx ^js token]
  (if (#{"fence" "code_block"} (.-type token))
    (token-map->line-span token)
    (or (some-> (matching-inline-token tokens idx token) token-map line-span)
        (token-map->line-span token))))

(defn- token-text [tokens idx ^js token]
  (case (.-type token)
    "fence" (.-content token)
    "code_block" (.-content token)
    (some-> (matching-inline-token tokens idx token) inline-text)))

(defn block-records [markdown-text]
  (let [tokens (vec (array-seq (markdown-js/tokenize #js {} (or markdown-text ""))))]
    (->> tokens
         (keep-indexed
           (fn [idx ^js token]
             (when-let [kind (token-kind token)]
               (when-not (.-hidden token)
                 (when-let [span (token-line-span tokens idx token)]
                   (assoc span :kind kind :text (token-text tokens idx token)))))))
         (map-indexed (fn [i b] (assoc b :index (inc i))))
         vec)))

(defn- ->block-ref [{:keys [kind index start-line end-line text]}]
  {:kind kind
   :index index
   :start-line start-line
   :end-line end-line
   :block-text-snippet (or text "")})

(defn selection-locator
  "Pure transform: build advisory DocumentExcerpt.locator from rendered-block
   records (with :text) and the selected text. Offsets are populated only when
   start and end blocks are the same block and the selected text appears inside
   the start block text."
  [start-block end-block selected-text]
  (let [same-block? (and (= (:index start-block) (:index end-block))
                         (= (:kind start-block) (:kind end-block)))
        base {:document-relative-path "document.md"
              :start-block (->block-ref start-block)
              :end-block   (->block-ref end-block)}]
    (if same-block?
      (let [block-text (or (:text start-block) "")
            idx        (.indexOf block-text selected-text)]
        (if (neg? idx)
          base
          (assoc base
                 :start-offset idx
                 :end-offset   (+ idx (count selected-text)))))
      base)))

(defn sync-block-metadata! [article markdown-text]
  (let [blocks   (block-records markdown-text)
        elements (array-seq (.querySelectorAll article block-selector))]
    (when (not= (count elements) (count blocks))
      (js/console.warn "[document-locator] DOM/block count mismatch"
                       (count elements) "elements," (count blocks) "blocks"))
    (doseq [[el block] (map vector elements blocks)]
      (.setAttribute el "data-grem-block-kind" (name (:kind block)))
      (.setAttribute el "data-grem-block-index" (str (:index block)))
      (.setAttribute el "data-grem-block-start-line" (str (:start-line block)))
      (.setAttribute el "data-grem-block-end-line" (str (:end-line block))))))

(defn selection-locator-from-dom
  "Read rendered-block data-* attrs via the DOM Range and return a locator map
   shaped like DocumentExcerpt.locator. Returns nil when endpoints lack block
   ancestors."
  [article sel]
  (let [range         (.getRangeAt sel 0)
        start-element (some-> (.-startContainer range) .-parentElement (.closest block-selector))
        end-element   (some-> (.-endContainer range) .-parentElement (.closest block-selector))
        parse-block   (fn [el]
                        (when el
                          {:kind       (some-> (.getAttribute el "data-grem-block-kind") keyword)
                           :index      (js/parseInt (.getAttribute el "data-grem-block-index") 10)
                           :start-line (js/parseInt (.getAttribute el "data-grem-block-start-line") 10)
                           :end-line   (js/parseInt (.getAttribute el "data-grem-block-end-line") 10)
                           :text       (.-textContent el)}))
        start-block   (parse-block start-element)
        end-block     (parse-block end-element)]
    (when (and start-block end-block)
      (selection-locator start-block end-block (.toString sel)))))
