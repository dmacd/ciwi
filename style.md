### Style Guidelines

## functional programming

- Do not introduce mutable state that breaks thread safety unless it is 
  critical to performance (and not just a constant overhead factor either). 
  Make a good argument and ask the user to approve before doing so if it 
  seems to be needed. It probably isnt.

  
## readability

Clojure code can be read almost like natural language if it is structured
well and avoids overly terse identifiers. Prefer longer, 
multi-word-identifiers for one-off variables; guts of a routine 
type stuff that is hard to read. 

Short or abbreviated variable names are fine if they are 
extremely common and ubiquitously threaded things e.g. "wb" for "wunderbaum instance".

Limit complex (in the sense of behavior, not strictly line count) functions. 
Break them up in to sub functions with readable names. Multiple closures, 
reductions, loops, recursion, etc should be a cue to maybe refactor.

### comments and docstrings
Public functions should have a one-line docstring if they are nontrival API 
components or call more than one or two other non-primitive functions.
 

## test styles
Not all "should" instructions that imply change from an previous 
implementation merit extensive test cases. For example, 
"such-and-such should take dependency injection instead of hard-coded 
registry for blah" DOES NOT NEED A BUNCH OF TEST CASES TO ENFORCE IT. Its 
sufficient to just refactor the constructors and call sites.