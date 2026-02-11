(ns gremllm.renderer.ui.markdown
  (:require ["snarkdown" :as snarkdown]
            ["sanitize-html" :as sanitize-html]))

(defn markdown->html [text]
  (-> text
      snarkdown
      (sanitize-html #js {:allowedTags #js ["h1" "h2" "h3" "h4" "h5" "h6"
                                            "p" "ul" "ol" "li"
                                            "code" "pre" "em" "strong" "a" "br"
                                            "blockquote" "hr"]
                          :allowedAttributes #js {:a #js ["href" "title"]
                                                  :code #js ["class"]}
                          :allowedSchemes #js ["http" "https" "mailto"]})))
