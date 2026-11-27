# expo-contacts-batch

Batched device address book writes for Expo / React Native. Create, update and remove **many contacts per native transaction**: one `CNSaveRequest` on iOS, one `applyBatch` on Android.

Use it next to [`expo-contacts`](https://docs.expo.dev/versions/latest/sdk/contacts/) when you write contacts in bulk, for example syncing a CRM's contacts to the phone so incoming calls show a name. Keep using `expo-contacts` for permissions and reads; ids are interchangeable between the two.

## Why

`expo-contacts` saves each contact in its own transaction. That's fine for a few contacts, but on bulk imports iOS's background processing of the address book falls behind and every later write waits on it:

| iOS simulator, 12,657 contacts | one per transaction (`expo-contacts`) | `expo-contacts-batch` (200 per call) |
| --- | --- | --- |
| Create all | starts ~120/s, decays to ~8/s (15+ min) | ~290/s steady, **43 s** |
| Remove all | ~1.6 s per contact once backlogged | **46 s** |

On an idle address book a single write takes ~10 ms; the slowdown comes from the transaction rate, not the store size.

## Install

```sh
npx expo install expo-contacts-batch
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

## Notes

- **Android ids** are aggregate contact ids, the same ones `expo-contacts` returns. `removeContacts` deletes the aggregate contact, just like `expo-contacts`' `removeContactAsync`.
- **iOS limited access (iOS 18+).** With access limited to selected contacts, the app can still add contacts (they become accessible to it), but it can't see the user's other contacts.
- Runs on a background queue; don't call it concurrently with other writes to the same contacts.

## License

MIT
