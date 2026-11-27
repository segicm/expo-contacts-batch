export type PhoneLabel = 'mobile' | 'home' | 'work' | 'main' | 'fax' | 'pager' | 'other';

export type EmailLabel = 'work' | 'home';

export interface BatchContact {
  /** Device contact id. Required for `updateContacts`, ignored by `addContacts`. */
  id?: string;
  /** `'company'` creates an organization card (iOS) — useful when there is no person name. */
  contactType?: 'person' | 'company';
  firstName?: string;
  lastName?: string;
  company?: string;
  phoneNumbers?: { label?: PhoneLabel; number: string }[];
  emails?: { label?: EmailLabel; email: string }[];
}

/** Aligned with the input: one entry per contact, in order. */
export type WriteResult = { id: string; error?: undefined } | { id?: undefined; error: string };

export type RemoveResult = { ok: true; error?: undefined } | { ok?: undefined; error: string };

/** False when the running binary was built without this package. */
export const isAvailable: boolean;

/**
 * Creates the contacts. Returns their new device ids, aligned with the input.
 *
 * Ids: on iOS, contact identifiers (the same ones expo-contacts uses). On Android, RAW
 * contact ids — not the aggregate ids expo-contacts uses. Android merges raw contacts it
 * thinks are the same person into one aggregate, sometimes after the insert, so an
 * aggregate id can disappear and can cover the user's own rows. A raw id only ever names
 * the row this package wrote. Use `snapshot()` (not `expo-contacts`) to read ids back.
 */
export function addContacts(contacts: BatchContact[]): Promise<WriteResult[]>;

/**
 * Replaces the given fields (names, company, phones, emails) of existing contacts.
 * iOS updates in place and keeps the id. Android replaces the contact (an in-place
 * update would merge old numbers in), so the returned id may differ — store it.
 * A contact that no longer exists returns `{ error: 'not_found' }` on iOS.
 */
export function updateContacts(contacts: (BatchContact & { id: string })[]): Promise<WriteResult[]>;

/**
 * Deletes the contacts. An id that is already gone counts as removed.
 * On Android only the given raw contacts are deleted (hard delete); anything the user
 * merged with them stays.
 */
export function removeContacts(ids: string[]): Promise<RemoveResult[]>;

export interface SnapshotContact {
  id: string;
  phoneNumbers: { number: string }[];
}

/**
 * Every contact on the device with its phone numbers, in the same id space as the calls
 * above: Android raw contacts / iOS non-unified contacts. Use it to check which of the
 * contacts you wrote still exist, and which numbers belong to the user's own contacts.
 */
export function snapshot(): Promise<SnapshotContact[]>;
