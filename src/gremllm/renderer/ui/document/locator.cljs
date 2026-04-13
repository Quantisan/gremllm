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

(defn- text-offsets [block-text selected-text]
  (let [start (.indexOf (or block-text "") (or selected-text ""))]
    (when (and (seq selected-text) (not (neg? start)))
      {:start-offset start
       :end-offset   (+ start (count selected-text))})))

(defn selection-debug [start-block end-block selected-text]
  (when (seq selected-text)
    (let [base {:block-kind       (:kind start-block)
                :block-index      (:index start-block)
                :block-start-line (:start-line start-block)
                :block-end-line   (:end-line start-block)}]
      (if (= (:index start-block) (:index end-block))
        (merge base (text-offsets (:text start-block) selected-text))
        base))))
