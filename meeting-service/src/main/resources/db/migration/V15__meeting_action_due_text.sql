-- Preserve grounded source wording without inferring a calendar date.
ALTER TABLE meeting_actions ADD COLUMN due_text VARCHAR(255);
