# HOST_PATH: source-only analysis and category diagnostic delta

Actual41894061064 remains HOST_PATH/CLOSURE partial STOP. Exact offending path/value is NOT retained and cannot be established here. Historical41888653120 generic STOP remains unrecoverable. No real host files were read or parsed in this analysis.

Source-level reproducible boundary: resolver substitutes $ORIGIN then sends expanded RUNPATH to canonical(), which rejects any literal '.' or '..' component. Synthetic $ORIGIN/../lib triggers HOST_PATH even though it might lexically remain under the snapshot host subtree. Descriptor capture separately permits bounded symlink '..' expansion under rootfd. These are different contracts, not proof of a bug: current strict RUNPATH grammar intentionally rejects parent components. The observed actual run might instead involve any other HOST_PATH site. This example does not identify the actual input and is not grounds for normalizing it.

No path acceptance change proposed. Strict canonical grammar and all caps, guards, roots, alias authentication, budgets, cleanup and environment limits are unchanged. No blanket normalization, traversal tolerance or extra directories. This proposal only attaches fixed-category diagnostics to the existing HOST_PATH Stop:
- context START, ROW_CANONICAL, ALIAS_COMPONENT, RUNPATH_EXPANDED or UNSPECIFIED;
- category NOT_STRING, NOT_ABSOLUTE, LENGTH, CHARSET, DOT_COMPONENT or PARENT_COMPONENT.
No input string, length value, segment name, raw exception, traceback or private path is emitted. Reason remains HOST_PATH. Wrapper copies only known fixed labels to static partial checkpoint. Source-level helper changed and is unapproved for push/execution.

75 distinct synthetic tests pass. Added six invalid structural category cases/private-text suppression, synthetic origin-parent rejection, start/row/alias labels and unchanged canonical accepted form. Existing deterministic literals, whitelist, cleanup and cap tests pass. No execution, retry or mutation outside this local source proposal.

This ZIP is an explicit DELTA over the diagnostic candidate at994289d8...; path-diagnostic-bindings.json covers only these members. It is not a full candidate manifest. A later full reviewed candidate needs a complete recomputed manifest. Existing trailing blank-line whitespace is preserved, no diffcheck override or source formatting performed here.
