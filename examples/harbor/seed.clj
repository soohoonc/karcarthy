(agent
 {:name "coding-agent"
  :description "Inspect, modify, and verify an unfamiliar repository."
  :model (candidate-model)
  :instructions (coding-instructions)
  :tools (candidate-tools)
  :input string?
  :output string?
  :max-turns 24})
