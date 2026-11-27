# Changelog

## 0.2.0

- **Android: ids are now raw contact ids** instead of aggregate contact ids. Android merges raw contacts into aggregates (sometimes after the insert), so aggregate ids could disappear, and removing by aggregate id could delete a user's own contact that Android had merged with one of yours. **If you stored ids from 0.1.0 on Android, don't pass them to 0.2.0's `updateContacts`/`removeContacts`.**
- **iOS:** contacts are fetched non-unified, so a contact the user linked to one of yours is never updated or deleted.
- New `snapshot()`: every contact with its phone numbers, in the same id space.
- **Android:** `removeContacts` (and therefore `updateContacts`) deletes each chunk with a single hard delete, about 5× faster on large removals.

## 0.1.0

- Initial release: `addContacts`, `updateContacts`, `removeContacts`.
