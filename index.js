import { requireOptionalNativeModule } from 'expo';

/**
 * Batched device address book writes: many contacts per native transaction
 * (one CNSaveRequest on iOS, one applyBatch on Android).
 *
 * The native module is optional on purpose: a binary built before this
 * package was added (e.g. one receiving an OTA update) simply reports
 * `isAvailable === false`, so callers can fall back to per-contact writes.
 */
const Native = requireOptionalNativeModule('ExpoContactsBatch');

export const isAvailable = !!Native;

const ensure = () => {
  if (!Native) {
    throw new Error(
      'expo-contacts-batch: native module not found. Rebuild the app (npx expo run:ios / run:android, or an EAS build) after installing.'
    );
  }
  return Native;
};

export const addContacts = (contacts) => ensure().addContacts(contacts);

export const updateContacts = (contacts) => ensure().updateContacts(contacts);

export const removeContacts = (ids) => ensure().removeContacts(ids.map(String));
