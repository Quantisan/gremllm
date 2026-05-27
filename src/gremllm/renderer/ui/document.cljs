(ns gremllm.renderer.ui.document
  (:require [gremllm.renderer.ui.markdown :as md]
            [gremllm.renderer.ui.document.diffs :as diffs]
            [gremllm.renderer.ui.document.highlights :as highlights]
            [gremllm.renderer.ui.document.locator :as locator]
            [gremllm.renderer.ui.document.gutter :as gutter]))

(defn- render-diff-segments [segments]
  (into [:div]
        (mapv (fn [{:keys [type content old-text new-text tool-call-id]}]
                (case type
                  :text       [:span content]
                  :diff-block [:div.diff-block
                               [:del old-text]
                               [:ins new-text]
                               [:div.diff-controls
                                [:button {:on {:click [[:topic.actions/accept-diff tool-call-id]]}}
                                 "Accept"]
                                [:button {:class "secondary outline"
                                          :on {:click [[:topic.actions/reject-diff tool-call-id]]}}
                                 "Reject"]]]))
              segments)))

;; SMELL: Two concerns share one lifecycle hook because they both need the
;; article DOM node after render.
;;
;; Concern 1 — Document indexing. sync-block-metadata! tags each rendered
;; element (<p>, <h2>, <li>) with data-attributes mapping it back to markdown
;; source lines. This is document infrastructure: it only depends on `content`,
;; and it serves two unrelated consumers — the text-selection-to-locator path
;; (when the user highlights text) and the gutter bar positioning below. It
;; changes only when the document content changes.
;;
;; Concern 2 — Topic–document overlay. Excerpt highlights, anchor highlights,
;; and gutter bar positioning are three visual channels for one domain idea:
;; showing where topics touch the document. Excerpt highlights paint CSS Custom
;; Highlight ranges over captured text. Anchor highlights paint the region a
;; topic is anchored to (active on topic switch, preview on gutter hover).
;; Gutter bars are absolutely positioned to align with their anchor's block
;; rects. Different visual channels, same underlying relationship.
;;
;; These two concerns have different change cadences. Document indexing changes
;; only when content changes. The overlay changes on topic switch, excerpt
;; add/remove, and mouse hover. But session-opts flattens them into one closure,
;; so a gutter-bar hover re-runs block metadata stamping, excerpt highlighting,
;; and everything else that had nothing to do with the hover.
;;
;; The gutter coupling is the most tangled: its declarative content (bar buttons
;; with colors and click handlers) is rendered by gutter/render-gutter as a
;; sibling of the article in ui.cljs. But its spatial layout is computed here,
;; inside the article's own lifecycle hook, by walking up to the parent and
;; querySelector-ing back down to the sibling. One visual element, two render
;; paths, joined by a DOM traversal that encodes a layout assumption.
(defn- on-render-sync [content excerpts session-opts]
  (fn [{:replicant/keys [node life-cycle]}]
    (if (= :replicant.life-cycle/unmount life-cycle)
      (highlights/clear-all!)
      (let [article node
            gutter-el (some-> article .-parentElement (.querySelector ".session-gutter"))]
        (locator/sync-block-metadata! article content)
        (highlights/sync! article excerpts)
        (highlights/sync-anchor! article (:active-anchor-text session-opts))
        (highlights/sync-anchor-preview! article (:preview-anchor-text session-opts))
        ;; gutter bars are positioned from block rects — requires sync-block-metadata! above
        (when gutter-el
          (gutter/sync! gutter-el article (:topics-map session-opts)))))))

(defn render-document [content pending-diffs excerpts session-opts]
  (if content
    (if (seq pending-diffs)
      (let [segments (diffs/compose content pending-diffs)]
        [:article.diff-mode (render-diff-segments segments)])
      [:article {:on                  {:mouseup [[:excerpt.actions/capture [:event/text-selection]]]}
                 :replicant/on-render (on-render-sync content excerpts session-opts)}
       (md/markdown->hiccup content)])
    [:article
     [:p {:style {:color      "var(--pico-muted-color)"
                  :font-style "italic"}}
      "No document open."]
     [:button {:on {:click [[:document.actions/pick]]}}
      "Open…"]]))
