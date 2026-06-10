(ns ciwi.alice.declarations)

(def operator-declarations
  "Explicit CIWI specs for the Python test_alice.py operator basis.

  CIWI operators do not yet carry Python's typed `specs`, so this table is the
  near-term equivalent of Python Operator._raw_specs for Wunderbaum indexing."
  [{:op :brange :input-specs [:int :int] :output-spec :array-int}
   {:op :repeat :input-specs [:int :array-int] :output-spec :array-int}
   {:op :repeat :input-specs [:int :array] :output-spec :array}
   {:op :repeat :input-specs [:int :string] :output-spec :string}

   {:op :add :input-specs [:int :int] :output-spec :int}
   {:op :add :input-specs [:array-int :int] :output-spec :array-int}
   {:op :add :input-specs [:int :array-int] :output-spec :array-int}
   {:op :add :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :add :input-specs [:float :float] :output-spec :float}
   {:op :add :input-specs [:array-float :float] :output-spec :array-float}
   {:op :add :input-specs [:array-float :array-float] :output-spec :array-float}

   {:op :mult :input-specs [:int :int] :output-spec :int}
   {:op :mult :input-specs [:array-int :int] :output-spec :array-int}
   {:op :mult :input-specs [:int :array-int] :output-spec :array-int}
   {:op :mult :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :mult :input-specs [:float :float] :output-spec :float}
   {:op :mult :input-specs [:array-float :float] :output-spec :array-float}
   {:op :mult :input-specs [:array-float :array-float] :output-spec :array-float}

   {:op :negate :input-specs [:int] :output-spec :int}
   {:op :negate :input-specs [:array-int] :output-spec :array-int}
   {:op :negate :input-specs [:float] :output-spec :float}
   {:op :negate :input-specs [:array-float] :output-spec :array-float}

   {:op :concat :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :concat :input-specs [:array :array] :output-spec :array}
   {:op :concat :input-specs [:string :string] :output-spec :string}

   {:op :insert :input-specs [:array-int :int :array-int] :output-spec :array-int}
   {:op :insert :input-specs [:array-int :array-int :array-int] :output-spec :array-int}
   {:op :insert :input-specs [:array-int :array :array] :output-spec :array}

   {:op :cumsum :input-specs [:array-int] :output-spec :array-int}
   {:op :cumsum :input-specs [:array-float] :output-spec :array-float}

   {:op :getitem :input-specs [:array-int :int] :output-spec :int}
   {:op :getitem :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :getitem :input-specs [:array-int :array-bool] :output-spec :array-int}
   {:op :getitem :input-specs [:array :int] :output-spec :unknown}
   {:op :getitem :input-specs [:array :array-int] :output-spec :array}

   {:op :lessthan :input-specs [:int :int] :output-spec :bool}
   {:op :lessthan :input-specs [:array-int :int] :output-spec :array-bool}
   {:op :lessthan :input-specs [:array-int :array-int] :output-spec :array-bool}
   {:op :equal :input-specs [:int :int] :output-spec :bool}
   {:op :equal :input-specs [:array-int :int] :output-spec :array-bool}
   {:op :equal :input-specs [:array-int :array-int] :output-spec :array-bool}

   {:op :map :input-specs [:operator :array-int] :output-spec :array-int}
   {:op :fix :input-specs [:unknown :operator] :output-spec :operator}])

(def python-dl-jitter
  "Python `default_rng(42).random() * 1e-6` tie-breakers for Alice elements.

  Python applies this when turning the task-domain operator basis into
  Wunderbaum graph elements. CIWI declarations are already concrete, so we keep
  the equivalent additive jitter at the declaration boundary."
  {[:brange [:int :int] :array-int] 0.000000554584787

   [:repeat [:int :array-int] :array-int] 0.000000832678197
   [:repeat [:int :array] :array] 0.000000669813994
   [:repeat [:int :string] :string] 0.000000226909348

   [:add [:int :int] :int] 0.000000827631172
   [:add [:array-int :int] :array-int] 0.000000758087740
   [:add [:int :array-int] :array-int] 0.000000758087740
   [:add [:array-int :array-int] :array-int] 0.000000631664399
   [:add [:float :float] :float] 0.000000063817255
   [:add [:array-float :float] :array-float] 0.000000970698025
   [:add [:array-float :array-float] :array-float] 0.000000354525968

   [:mult [:int :int] :int] 0.000000778383496
   [:mult [:array-int :int] :array-int] 0.000000466721003
   [:mult [:int :array-int] :array-int] 0.000000466721003
   [:mult [:array-int :array-int] :array-int] 0.000000194638709
   [:mult [:float :float] :float] 0.000000893121122
   [:mult [:array-float :float] :array-float] 0.000000154289491
   [:mult [:array-float :array-float] :array-float] 0.000000043803766

   [:negate [:int] :int] 0.000000683048953
   [:negate [:array-int] :array-int] 0.000000967509733
   [:negate [:float] :float] 0.000000744762156
   [:negate [:array-float] :array-float] 0.000000325825358

   [:concat [:array-int :array-int] :array-int] 0.000000129921505
   [:concat [:array :array] :array] 0.000000469555811
   [:concat [:string :string] :string] 0.000000370459706

   [:insert [:array-int :int :array-int] :array-int] 0.000000458915775
   [:insert [:array-int :array-int :array-int] :array-int] 0.000000139796999
   [:insert [:array-int :array :array] :array] 0.000000139796999

   [:cumsum [:array-int] :array-int] 0.000000471096206
   [:cumsum [:array-float] :array-float] 0.000000565236107

   [:getitem [:array-int :int] :int] 0.000000804764358
   [:getitem [:array-int :array-int] :array-int] 0.000000664850857
   [:getitem [:array-int :array-bool] :array-int] 0.000000007362269
   [:getitem [:array :int] :unknown] 0.000000312366641
   [:getitem [:array :array-int] :array] 0.000000139752483

   [:lessthan [:int :int] :bool] 0.000000764998857
   [:lessthan [:array-int :int] :array-bool] 0.000000303950099
   [:lessthan [:array-int :array-int] :array-bool] 0.000000553579401
   [:equal [:int :int] :bool] 0.000000030817835
   [:equal [:array-int :int] :array-bool] 0.000000214584674
   [:equal [:array-int :array-int] :array-bool] 0.000000214584674

   [:map [:operator :array-int] :array-int] 0.000000094177349
   [:fix [:unknown :operator] :operator] 0.000000128113633})
