import Contacts
import ExpoModulesCore

/// Batched address book writes. Each call is one
/// CNSaveRequest instead of one per contact: per-transaction commits are what
/// make bulk imports crawl (iOS's background indexing falls behind and every
/// later write waits on it). A CNSaveRequest is all-or-nothing, so when one
/// fails the batch is retried one contact at a time and only the bad record
/// reports an error.
public class ExpoContactsBatchModule: Module {
  private let store = CNContactStore()

  private static let keys: [CNKeyDescriptor] = [
    CNContactIdentifierKey,
    CNContactTypeKey,
    CNContactGivenNameKey,
    CNContactFamilyNameKey,
    CNContactOrganizationNameKey,
    CNContactPhoneNumbersKey,
    CNContactEmailAddressesKey,
  ] as [CNKeyDescriptor]

  public func definition() -> ModuleDefinition {
    Name("ExpoContactsBatch")

    AsyncFunction("addContacts") { (inputs: [[String: Any]]) -> [[String: Any]] in
      let container = self.store.defaultContainerIdentifier()
      let contacts = inputs.map { input -> CNMutableContact in
        let contact = CNMutableContact()
        Self.apply(input, to: contact)
        return contact
      }
      return self.save(contacts) { request, contact in
        request.add(contact, toContainerWithIdentifier: container)
      } result: { contact in
        ["id": contact.identifier]
      }
    }

    AsyncFunction("updateContacts") { (inputs: [[String: Any]]) -> [[String: Any]] in
      let found = try self.fetchMutable(ids: inputs.compactMap { $0["id"] as? String })
      var results = [[String: Any]](repeating: ["error": "not_found"], count: inputs.count)
      var targets: [(index: Int, contact: CNMutableContact)] = []
      for (index, input) in inputs.enumerated() {
        guard let id = input["id"] as? String, let contact = found[id] else { continue }
        Self.apply(input, to: contact)
        targets.append((index, contact))
      }
      let saved = self.save(targets.map { $0.contact }) { request, contact in
        request.update(contact)
      } result: { contact in
        ["id": contact.identifier]
      }
      for (offset, target) in targets.enumerated() {
        results[target.index] = saved[offset]
      }
      return results
    }

    AsyncFunction("removeContacts") { (ids: [String]) -> [[String: Any]] in
      let found = try self.fetchMutable(ids: ids)
      // A contact that is already gone counts as removed.
      var results = [[String: Any]](repeating: ["ok": true], count: ids.count)
      var targets: [(index: Int, contact: CNMutableContact)] = []
      for (index, id) in ids.enumerated() {
        if let contact = found[id] { targets.append((index, contact)) }
      }
      let deleted = self.save(targets.map { $0.contact }) { request, contact in
        request.delete(contact)
      } result: { _ in
        ["ok": true]
      }
      for (offset, target) in targets.enumerated() {
        results[target.index] = deleted[offset]
      }
      return results
    }
  }

  /// Runs every contact through one CNSaveRequest; if that throws, retries
  /// each contact in its own request so one bad record can't fail the rest.
  private func save(
    _ contacts: [CNMutableContact],
    stage: (CNSaveRequest, CNMutableContact) -> Void,
    result: (CNMutableContact) -> [String: Any]
  ) -> [[String: Any]] {
    if contacts.isEmpty { return [] }
    let request = CNSaveRequest()
    contacts.forEach { stage(request, $0) }
    do {
      try store.execute(request)
      return contacts.map(result)
    } catch {
      return contacts.map { contact in
        let single = CNSaveRequest()
        stage(single, contact)
        do {
          try store.execute(single)
          return result(contact)
        } catch {
          return ["error": error.localizedDescription]
        }
      }
    }
  }

  private func fetchMutable(ids: [String]) throws -> [String: CNMutableContact] {
    if ids.isEmpty { return [:] }
    let predicate = CNContact.predicateForContacts(withIdentifiers: ids)
    let contacts = try store.unifiedContacts(matching: predicate, keysToFetch: Self.keys)
    var byId = [String: CNMutableContact]()
    for contact in contacts {
      if let mutable = contact.mutableCopy() as? CNMutableContact {
        byId[contact.identifier] = mutable
      }
    }
    return byId
  }

  private static func apply(_ input: [String: Any], to contact: CNMutableContact) {
    contact.contactType = (input["contactType"] as? String) == "company" ? .organization : .person
    contact.givenName = input["firstName"] as? String ?? ""
    contact.familyName = input["lastName"] as? String ?? ""
    contact.organizationName = input["company"] as? String ?? ""

    let phones = input["phoneNumbers"] as? [[String: Any]] ?? []
    contact.phoneNumbers = phones.compactMap { phone in
      guard let number = phone["number"] as? String, !number.isEmpty else { return nil }
      return CNLabeledValue(label: phoneLabel(phone["label"] as? String), value: CNPhoneNumber(stringValue: number))
    }

    let emails = input["emails"] as? [[String: Any]] ?? []
    contact.emailAddresses = emails.compactMap { email in
      guard let address = email["email"] as? String, !address.isEmpty else { return nil }
      let label = (email["label"] as? String) == "home" ? CNLabelHome : CNLabelWork
      return CNLabeledValue(label: label, value: address as NSString)
    }
  }

  private static func phoneLabel(_ label: String?) -> String {
    switch label {
    case "home": return CNLabelHome
    case "work": return CNLabelWork
    case "main": return CNLabelPhoneNumberMain
    case "fax": return CNLabelPhoneNumberWorkFax
    case "pager": return CNLabelPhoneNumberPager
    case "other": return CNLabelOther
    default: return CNLabelPhoneNumberMobile
    }
  }
}
