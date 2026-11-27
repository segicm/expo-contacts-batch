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
 * Ids are the same ones expo-contacts uses (`getContactsAsync`, `removeContactAsync`, …).
 */
export function addContacts(contacts: BatchContact[]): Promise<WriteResult[]>;

/**
 * Replaces the given fields (names, company, phones, emails) of existing contacts.
 * iOS updates in place and keeps the id. Android replaces the contact (an in-place
 * update would merge old numbers in), so the returned id may differ — store it.
 * A contact that no longer exists returns `{ error: 'not_found' }` on iOS.
 */
export function updateContacts(contacts: (BatchContact & { id: string })[]): Promise<WriteResult[]>;

/** Deletes the contacts. An id that is already gone counts as removed. */
export function removeContacts(ids: string[]): Promise<RemoveResult[]>;
