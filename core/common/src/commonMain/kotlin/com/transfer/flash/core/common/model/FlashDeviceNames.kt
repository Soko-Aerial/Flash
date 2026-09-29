package com.transfer.flash.core.common.model

/**
 * Default device names: "Flash" plus an animal or a fruit ("Flash Otter", "Flash Mango"), picked from the
 * device id so a device keeps the same name across restarts.
 *
 * Replaces the old defaults, "Flash Desktop" on every PC and "Flash <model>" on phones (2026-09-29, ERROR-077):
 * two PCs, or two phones of the same model, advertised the same mDNS instance name. Besides being impossible to
 * tell apart, the shared name let JmDNS combine one device's TXT record (its id) with the other's address, so a
 * PC dialled itself under the other PC's id.
 *
 * With [WORDS].size choices two devices can still draw the same word; the name is a label, never an identity
 * (the device id and its pinned key are), and the owner can rename either device.
 */
public object FlashDeviceNames {

    /** The default name for [deviceId]. Stable: the same id always gives the same name, on every platform. */
    public fun forDeviceId(deviceId: String): String = "Flash ${WORDS[indexFor(deviceId)]}"

    /** FNV-1a over the id's UTF-16 units: defined here rather than `hashCode` so every platform agrees. */
    private fun indexFor(deviceId: String): Int {
        var hash = FNV_OFFSET
        for (ch in deviceId) {
            hash = (hash xor ch.code.toUInt()) * FNV_PRIME
        }
        return (hash % WORDS.size.toUInt()).toInt()
    }

    private const val FNV_OFFSET: UInt = 2166136261u
    private const val FNV_PRIME: UInt = 16777619u

    /** Animals, then fruits. Append only: reordering renames every device that still has its default name. */
    internal val WORDS: List<String> = listOf(
        // Animals
        "Bunny", "Panda", "Otter", "Fox", "Koala", "Tiger", "Falcon", "Dolphin", "Penguin", "Owl",
        "Lynx", "Badger", "Beaver", "Bison", "Camel", "Cheetah", "Coyote", "Crane", "Deer", "Eagle",
        "Ferret", "Gazelle", "Gecko", "Giraffe", "Hawk", "Hedgehog", "Heron", "Jaguar", "Lemur", "Leopard",
        "Lion", "Llama", "Meerkat", "Moose", "Narwhal", "Ocelot", "Orca", "Parrot", "Pelican", "Puffin",
        "Quokka", "Raccoon", "Raven", "Robin", "Seal", "Sparrow", "Squirrel", "Swan", "Toucan", "Turtle",
        "Walrus", "Wolf", "Wombat", "Yak", "Zebra", "Alpaca", "Hamster", "Kitten", "Puppy", "Hippo",
        // Fruits
        "Apple", "Apricot", "Banana", "Blueberry", "Cherry", "Coconut", "Cranberry", "Fig", "Grape", "Guava",
        "Kiwi", "Lemon", "Lime", "Lychee", "Mango", "Melon", "Nectarine", "Olive", "Orange", "Papaya",
        "Peach", "Pear", "Plum", "Pomelo", "Quince", "Raspberry", "Strawberry", "Tangerine", "Watermelon",
        "Pineapple",
    )
}
