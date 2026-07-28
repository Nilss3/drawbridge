package app.drawbridge.herald.components

import mozilla.components.concept.storage.Address
import mozilla.components.concept.storage.CreditCard
import mozilla.components.concept.storage.CreditCardEntry
import mozilla.components.concept.storage.CreditCardNumber
import mozilla.components.concept.storage.CreditCardsAddressesStorageDelegate
import mozilla.components.concept.storage.ManagedKey

/**
 * herald saves logins but deliberately stores no credit cards or addresses: this
 * is a browser for a managed device, and there is no reason for it to hold
 * payment details.
 *
 * The Gecko runtime's autocomplete delegate requires both halves, so this
 * supplies an inert one.
 */
object NoCreditCardsOrAddresses : CreditCardsAddressesStorageDelegate {

    override suspend fun getOrGenerateKey(): ManagedKey = ManagedKey(key = "")

    override suspend fun decrypt(
        key: ManagedKey,
        encryptedCardNumber: CreditCardNumber.Encrypted,
    ): CreditCardNumber.Plaintext? = null

    override suspend fun onAddressesFetch(): List<Address> = emptyList()

    override suspend fun onAddressSave(address: Address) = Unit

    override suspend fun onCreditCardsFetch(): List<CreditCard> = emptyList()

    override suspend fun onCreditCardSave(creditCard: CreditCardEntry) = Unit
}
