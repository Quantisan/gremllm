(ns gremllm.renderer.ui.document)

(defn render-document-stub []
  [:article
   [:header
    [:hgroup
     [:h2 "Project Atlas — Investment Memo"]
     [:p "Draft | PE Due Diligence | Q1 2026"]]]
   [:section
    [:h3 "Executive Summary"]
    [:p "Target Co. represents a compelling platform acquisition in the industrial
         automation space. Revenue of $142M (FY2025) with 23% organic growth and
         68% gross margins. Management team has executed four successful tuck-in
         acquisitions over the past three years."]]
   [:section
    [:h3 "Investment Thesis"]
    [:ol
     [:li "Market tailwinds from manufacturing reshoring driving 15% sector growth"]
     [:li "Sticky customer base with 95%+ net revenue retention"]
     [:li "Proven M&A playbook with clear pipeline of bolt-on targets"]
     [:li "Margin expansion opportunity through operational improvements"]]]
   [:section
    [:h3 "Key Risks"]
    [:ul
     [:li [:strong "Customer concentration:"] " Top 3 customers represent 34% of revenue"]
     [:li [:strong "Integration risk:"] " Two acquisitions closed in last 12 months still being integrated"]
     [:li [:strong "Key person dependency:"] " CTO and VP Engineering critical to product roadmap"]]]])
