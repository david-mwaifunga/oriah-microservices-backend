-- Phase 5 -- amending a received consignment: room for what a corrected revision needs.
--
-- A received consignment is frozen (V5): its header, lines, cost lines and typed shares can never change, and every
-- row of its recorded allocation is append-only. A correction (the courier's charge was keyed K800 and was really
-- K8,000) is therefore a NEW REVISION of the allocation, with a reason, never an edit. The revision's recorded inputs
-- (allocation_revision.inputs, in whole ngwee) are the record of which goods and which costs it was worked out from,
-- so a revision can carry costs that were never cost lines of the draft, and its goods can differ from the lines'.
-- Nothing already recorded is touched, and V2-V6 are not edited.

-- What each cost put on each line is recorded per revision. A revision >= 2 may share a cost that is not a cost_line
-- (a corrected courier charge, a late duty): the revision's own recorded inputs say which costs it had, so a share no
-- longer needs a cost_line row behind it. (The column keeps its name; for revision 1 it is still the cost_line's id.)
ALTER TABLE allocation_cost_share DROP CONSTRAINT fk_allocation_cost_share_cost;

-- Whether the consignment is still an ESTIMATE as of this revision. Revision 1 is the receipt and leaves it unsaid
-- (the consignment's own flag stands: it is frozen, and for the opening consignment it can never be cleared). Every
-- later revision says: a shipment keyed from the courier's provisional bill is exactly what the final bill clears.
-- (Written `revision <= 1`, not `= 1`, so that a revision number below 1 is refused by the number's own check
-- (ck_allocation_revision_number) and its message, not by this one.)
ALTER TABLE allocation_revision ADD COLUMN is_estimated BOOLEAN;
ALTER TABLE allocation_revision ADD CONSTRAINT ck_allocation_revision_estimated
    CHECK (revision <= 1 OR is_estimated IS NOT NULL);

-- A line's goods are above zero in every revision: a correction can never make goods free.
ALTER TABLE allocation_line ADD CONSTRAINT ck_allocation_line_goods CHECK (goods_total > 0);
