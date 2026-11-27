# expo-contacts-batch

Batched device address book writes for Expo / React Native. Create, update and remove **many contacts per native transaction**: one `CNSaveRequest` on iOS, one `applyBatch` on Android.

Use it next to [`expo-contacts`](https://docs.expo.dev/versions/latest/sdk/contacts/) when you write contacts in bulk, for example syncing a CRM's contacts to the phone so incoming calls show a name. Keep using `expo-contacts` for permissions; use this package's `snapshot()` to read back the contacts it wrote (see [Ids](#ids)).

## Why

`expo-contacts` saves each contact in its own transaction. That's fine for a few contacts, but on bulk imports iOS's background processing of the address book falls behind and every later write waits on it:

| 12,657 contacts | one per transaction (`expo-contacts`) | `expo-contacts-batch` (200 per call) |
| --- | --- | --- |
| iOS simulator: create all | starts ~120/s, decays to ~8/s (15+ min) | ~290/s steady, **43 s** |
| iOS simulator: remove all | ~1.6 s per contact once backlogged | **46 s** |
| Android emulator (API 34): remove 11,255 | ~17/s (~11 min) | **75 s** |

On an idle address book a single write takes ~10 ms; the slowdown comes from the transaction rate, not the store size.

## Install

Not on npm yet, so install from GitHub, pinned to a release tag:

```sh
npm install github:segicm/expo-contacts-batch#v0.2.0
# or: yarn add expo-contacts-batch@github:segicm/expo-contacts-batch#v0.2.0
```

It contains native code, so rebuild the app (`npx expo run:ios`, `npx expo run:android`, or an EAS build). It won't run in Expo Go.

**Permissions.** This package doesn't request contacts access. Ask for it first, e.g. with `expo-contacts`' `requestPermissionsAsync()`, and add the usual config: `NSContactsUsageDescription` on iOS, `READ_CONTACTS` and `WRITE_CONTACTS` on Android (the `expo-contacts` config plugin sets these).

## Usage

```js
import * as ContactsBatch from 'expo-contacts-batch';

const results = await ContactsBatch.addContacts([
  {
    firstName: 'Ann',
    lastName: 'Lee',
    phoneNumbers: [{ label: 'mobile', number: '+15550100001' }],
    emails: [{ label: 'work', email: 'ann@example.com' }],
  },
  { contactType: 'company', company: 'Acme Plumbing', phoneNumbers: [{ number: '+15550100002' }] },
]);
// → [{ id: '…' }, { id: '…' }]  (or { error } for a record that failed)

await ContactsBatch.updateContacts([{ id: results[0].id, firstName: 'Anne', lastName: 'Lee', phoneNumbers: [{ number: '+15550100009' }] }]);

await ContactsBatch.removeContacts(results.map((r) => r.id));

// Which of "my" contacts still exist, and which numbers the user already has:
const onDevice = await ContactsBatch.snapshot(); // [{ id, phoneNumbers: [{ number }] }]
```

Hand it chunks (a few hundred contacts per call works well) rather than one giant array, so you can show progress, cancel between chunks, and save state as you go.

### Falling back on older binaries

`isAvailable` is `false` when the running binary was built without this package, for example an app that receives your new JS through an OTA update. Fall back to `expo-contacts` in that case:

```js
import * as ContactsBatch from 'expo-contacts-batch';
import * as Contacts from 'expo-contacts/legacy';

const add = ContactsBatch.isAvailable
  ? (list) => ContactsBatch.addContacts(list)
  : async (list) => {
      const out = [];
      for (const c of list) {
        try {
          out.push({ id: await Contacts.addContactAsync(c) });
        } catch (e) {
          out.push({ error: e.message });
        }
      }
      return out;
    };
```

## API

All functions take an array and resolve to an array **aligned with the input**. One bad record never rejects the whole call; it gets `{ error }` in its slot.

### `addContacts(contacts) → Promise<({ id } | { error })[]>`

Creates contacts in the default container / local account. Returns their device ids.

### `updateContacts(contacts) → Promise<({ id } | { error })[]>`

Sets `contactType`, `firstName`, `lastName`, `company`, `phoneNumbers` and `emails` on existing contacts (fields you omit are cleared, so pass the full record).

- **iOS** updates in place and keeps the id. A missing contact returns `{ error: 'not_found' }`.
- **Android** replaces the contact (delete + insert), because an in-place update merges the old numbers in. **The returned id can differ, so store it.**

### `removeContacts(ids) → Promise<({ ok: true } | { error })[]>`

Deletes contacts. An id that no longer exists counts as removed.

### `snapshot() → Promise<{ id, phoneNumbers: { number }[] }[]>`

Every contact on the device with its phone numbers, in the same id space as the calls above. Use it to see which contacts you wrote still exist (the user may have deleted some) and which numbers belong to the user's own contacts.

### `isAvailable: boolean`

Whether the native module is present in the running binary.

### Contact shape

```ts
{
  id?: string;                         // updateContacts only
  contactType?: 'person' | 'company';  // 'company' → organization card on iOS
  firstName?: string;
  lastName?: string;
  company?: string;
  phoneNumbers?: { label?: 'mobile' | 'home' | 'work' | 'main' | 'fax' | 'pager' | 'other'; number: string }[];
  emails?: { label?: 'work' | 'home'; email: string }[];
}
```

Only these fields are written. Photos, addresses, notes and so on are not supported (yet).

## How failures are handled

A native batch is all-or-nothing: if any contact in it fails, nothing in it is saved. When that happens the module retries that batch one contact at a time, so only the bad record ends up with `{ error }`. On Android, batches are split to stay under the contacts provider's 500-operation limit.

## Ids

- **iOS:** contact identifiers, read without unifying. A contact the user links to one of yours stays a separate record, so updates and deletes never touch theirs.
- **Android: raw contact ids**, not the aggregate contact ids `expo-contacts` uses. Android merges raw contacts it considers the same person into one aggregate, sometimes after the insert, so a stored aggregate id can vanish (and a naive sync then re-creates the contact every time). Deleting by aggregate id would also delete the user's own contact if it was merged with yours. Raw ids only ever name the rows this package wrote. Removal is a hard delete of those rows.

Because of this, read ids back with `snapshot()`, not `expo-contacts`' `getContactsAsync` (which returns aggregate ids on Android).

## Notes

- **iOS limited access (iOS 18+).** With access limited to selected contacts, the app can still add contacts (they become accessible to it), but it can't see the user's other contacts.
- Runs on a background queue; don't call it concurrently with other writes to the same contacts.

## License

MIT
