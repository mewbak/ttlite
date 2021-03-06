-- projections of a dependent product

fstD = \ (a : Set) (b : (forall (_ : a). Set)) (p : (exists (x : a) . b x)) ->
            elim
                (exists (x : a) . b x)
                (\ (_ : exists (x : a) . b x) -> a)
                (\(x : a) (y : b x) -> x)
                p;

sndD = \ (a : Set) (b : (forall (_ : a). Set)) (p : (exists (x : a) . b x)) ->
            elim
                (exists (x : a) . b x)
                (\ (p : (exists (x : a) . b x)) -> b (fstD a b p))
                (\(x : a) (y : b x) -> y)
                p;
dproduct_id :
    forall
        (a : Set) (b : (forall (_ : a). Set)) (p : (exists (x : a) . b x)) . exists (x : a) . b x;

dproduct_id =
    \ (a : Set) (b : (forall (_ : a). Set)) (p : (exists (x : a) . b x)) ->
        elim
            (exists (x : a) . b x)
            (\ (_ : exists (x : a) . b x) -> exists (x : a) . b x)
            (\(x : a) (y : b x) -> dpair (exists (x : a) . b x) x y)
            p;

