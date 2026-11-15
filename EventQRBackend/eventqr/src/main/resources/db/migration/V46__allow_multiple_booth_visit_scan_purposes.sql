-- An event can have several booths, each its own BOOTH_VISIT scan purpose (own name, own rules,
-- own per-attendee scan limit through transaction_logs(registration_id, scan_purpose_id)).
--
-- scan_purposes had two identical unique indexes on (event_id, code), so an event could hold only
-- one purpose per code. Every other code stays single per event: findByEventIdAndCode(...) is an
-- Optional lookup (REWARD_REDEMPTION_SCAN) and would throw on a duplicate. Only BOOTH_VISIT is exempt.
DROP INDEX IF EXISTS public.scan_purposes_event_code_uniq;
DROP INDEX IF EXISTS public.ux_scan_purposes_event_code;

CREATE UNIQUE INDEX IF NOT EXISTS ux_scan_purposes_event_code_single
    ON public.scan_purposes (event_id, code)
    WHERE code <> 'BOOTH_VISIT';
