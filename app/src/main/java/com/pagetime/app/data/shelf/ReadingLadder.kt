package com.pagetime.app.data.shelf

/**
 * A reading path, in the order it is meant to be read.
 *
 * WHY AN ORDER AND NOT A LIST OF A HUNDRED TITLES
 *
 * "What should I read" is answered badly by a set. The famous lists — the
 * Modern Library hundred, the Norwegian Book Club hundred — are ranked by
 * merit, which tells a reader what is good and nothing at all about where to
 * start. A reader who takes them at face value opens Ulysses on a Tuesday and
 * stops reading for a year.
 *
 * A ladder is ranked by readiness instead. Homer before Virgil because Virgil
 * is answering Homer; Hume before Kant because Kant says so himself. That
 * ordering is the editorial judgement, and it is the part of this feature that
 * a catalogue cannot supply.
 *
 * WHY THESE BOOKS
 *
 * The spine is Adler's Great Books and the St John's College programme, which
 * are stable, documented, and — the thing that matters here — almost entirely
 * pre-1900, so nearly all of it is in the catalogues the app can actually
 * serve. A list chosen for fame rather than for age would be mostly
 * unopenable: the Modern Library hundred is roughly four-fifths still in
 * copyright, and a shelf where four books in five are dead ends teaches the
 * reader to stop tapping.
 *
 * The twentieth century is thin here for that reason and no other. Where a
 * book belongs on the ladder but cannot be had, it stays on the ladder and
 * says so.
 *
 * NO COPYRIGHT CLAIMS ANYWHERE IN THIS FILE
 *
 * Nothing here records whether a work is in the public domain. That varies by
 * country, changes with time, and is not a thing this app is in a position to
 * assert. Availability is discovered by asking the catalogues and is a
 * statement about them — "we cannot fetch this for you" — never about the law.
 */
data class LadderEntry(
    /** Stable key. Persisted as a shelf slot, so it never changes. */
    val key: String,
    val title: String,
    val author: String,
    val stage: LadderStage,
    /** One line on why it earns its place, shown under the title. */
    val note: String,
    /**
     * Other surnames a catalogue might file this author under.
     *
     * Transliteration is the whole reason this exists. The ladder says
     * Dostoevsky and Gutenberg says Dostoyevsky, and since the author is the
     * gate the matcher uses, that one vowel would report Crime and Punishment
     * as a book we cannot supply. Aliases are listed explicitly rather than
     * solved with fuzzy matching, because a matcher loose enough to bridge
     * that gap is also loose enough to confuse two real authors.
     */
    val authorAliases: List<String> = emptyList(),
) {
    /** What to ask a catalogue for. */
    val searchQuery: String get() = "$title $author"
}

enum class LadderStage(val label: String, val blurb: String) {
    GREEKS(
        "The Greeks",
        "Where the arguments start. Almost everything later is a reply to something here.",
    ),
    ROME(
        "Rome and after",
        "The inheritance, and the first people to write about how to live with it.",
    ),
    MEDIEVAL(
        "Faith, then doubt",
        "A thousand years in which the questions stay and the answers stop being shared.",
    ),
    MODERN_MIND(
        "The modern mind",
        "The century that decided knowledge needed a method, and the ones that argued about it.",
    ),
    NOVEL(
        "The novel",
        "The form that took over, once prose could hold a whole interior life.",
    ),
}

object ReadingLadders {

    const val GREAT_BOOKS_SHELF_ID = "ladder-great-books"

    const val GREAT_BOOKS_NAME = "The ladder"

    const val GREAT_BOOKS_NOTE =
        "An ordered path through the books most other books are arguing with. " +
            "Read down, not across — each one is easier for having read the last."

    /**
     * The path, in order. Position is the index: the list IS the sequence, so
     * there is no second place for the order to disagree with this one.
     */
    val greatBooks: List<LadderEntry> = listOf(
        // --- The Greeks ---
        e("iliad", "The Iliad", "Homer", LadderStage.GREEKS,
            "The oldest thing in the West that still reads like it was written about people."),
        e("odyssey", "The Odyssey", "Homer", LadderStage.GREEKS,
            "The first story about wanting to go home, and the shape of most since."),
        e("oresteia", "The Oresteia", "Aeschylus", LadderStage.GREEKS,
            "Vengeance becomes law across three plays. The invention of the courtroom as an idea."),
        e("oedipus", "Oedipus Rex", "Sophocles", LadderStage.GREEKS,
            "A man investigates a crime and the answer is himself. Nothing has beaten its construction."),
        e("antigone", "Antigone", "Sophocles", LadderStage.GREEKS,
            "Conscience against the state, argued so well that neither side is wrong."),
        e("bacchae", "The Bacchae", "Euripides", LadderStage.GREEKS,
            "What happens to a city that legislates against ecstasy."),
        e("herodotus", "The Histories", "Herodotus", LadderStage.GREEKS,
            "The first person to ask why things happened rather than just what happened."),
        e("thucydides", "History of the Peloponnesian War", "Thucydides", LadderStage.GREEKS,
            "Power described without comfort. Still assigned in war colleges."),
        e("apology", "Apology", "Plato", LadderStage.GREEKS,
            "Start Plato here. A man on trial for asking questions, refusing to stop."),
        e("republic", "The Republic", "Plato", LadderStage.GREEKS,
            "Justice, the cave, and the first argument that the best rulers do not want to rule."),
        e("ethics", "Nicomachean Ethics", "Aristotle", LadderStage.GREEKS,
            "How to be good, worked out like a practical problem rather than a commandment."),
        e("poetics", "Poetics", "Aristotle", LadderStage.GREEKS,
            "Why stories work. Every screenwriting manual is a footnote to it."),

        // --- Rome and after ---
        e("aeneid", "The Aeneid", "Virgil", LadderStage.ROME,
            "Homer rewritten for an empire that needed a founding myth. Read after the Odyssey.",
            aliases = listOf("Vergil")),
        e("lucretius", "On the Nature of Things", "Lucretius", LadderStage.ROME,
            "Atoms, mortality and no gods worth fearing — in verse, two thousand years early."),
        e("meditations", "Meditations", "Marcus Aurelius", LadderStage.ROME,
            "The private notebook of a man with absolute power talking himself into decency."),
        e("enchiridion", "The Enchiridion", "Epictetus", LadderStage.ROME,
            "Stoicism from a former slave, which is the version with teeth."),
        e("plutarch", "Plutarch's Lives", "Plutarch", LadderStage.ROME,
            "Biography invented as a moral instrument. Shakespeare read it and took plots."),
        e("confessions", "Confessions", "Augustine", LadderStage.ROME,
            "The first autobiography with an interior. Memory, time and guilt, examined."),
        e("boethius", "The Consolation of Philosophy", "Boethius", LadderStage.ROME,
            "Written awaiting execution. The bridge from the ancient world to the medieval one."),

        // --- Faith, then doubt ---
        e("divine-comedy", "The Divine Comedy", "Dante Alighieri", LadderStage.MEDIEVAL,
            "The whole medieval cosmos as architecture you walk through."),
        e("canterbury", "The Canterbury Tales", "Geoffrey Chaucer", LadderStage.MEDIEVAL,
            "English becomes a literary language, and it arrives funny and filthy."),
        e("prince", "The Prince", "Niccolò Machiavelli", LadderStage.MEDIEVAL,
            "Politics described as it is rather than as it should be. Still shocking for that."),
        e("montaigne", "Essays", "Michel de Montaigne", LadderStage.MEDIEVAL,
            "The essay invented, by a man who found himself the most interesting available subject."),
        e("quixote", "Don Quixote", "Miguel de Cervantes", LadderStage.MEDIEVAL,
            "The first modern novel, and still the funniest argument about reading too much."),
        e("hamlet", "Hamlet", "William Shakespeare", LadderStage.MEDIEVAL,
            "Start Shakespeare here: the play where a character first seems to think on the page."),
        e("lear", "King Lear", "William Shakespeare", LadderStage.MEDIEVAL,
            "The bleakest thing in the language, and the one most people end up ranking first."),
        e("tempest", "The Tempest", "William Shakespeare", LadderStage.MEDIEVAL,
            "Late, strange and forgiving. Read it last of the three."),
        e("paradise-lost", "Paradise Lost", "John Milton", LadderStage.MEDIEVAL,
            "English poetry's most ambitious attempt, with the best villain in it by accident."),

        // --- The modern mind ---
        e("descartes", "Meditations on First Philosophy", "René Descartes", LadderStage.MODERN_MIND,
            "Doubt everything and see what survives. Modern philosophy starts from this page."),
        e("pascal", "Pensées", "Blaise Pascal", LadderStage.MODERN_MIND,
            "Fragments from a mathematician who thought reason had a limit and named it."),
        e("leviathan", "Leviathan", "Thomas Hobbes", LadderStage.MODERN_MIND,
            "Why we tolerate a state at all, argued from the worst assumptions about ourselves."),
        e("locke-govt", "Second Treatise of Government", "John Locke", LadderStage.MODERN_MIND,
            "The other answer to Hobbes, and the one the American founders copied out."),
        e("spinoza", "Ethics", "Baruch Spinoza", LadderStage.MODERN_MIND,
            "God, mind and freedom proved like geometry. Slow going and worth it."),
        e("hume", "An Enquiry Concerning Human Understanding", "David Hume", LadderStage.MODERN_MIND,
            "Causation dismantled so cleanly that Kant said it woke him up. Read it before Kant."),
        e("rousseau", "The Social Contract", "Jean-Jacques Rousseau", LadderStage.MODERN_MIND,
            "Born free and everywhere in chains — the sentence that armed a revolution."),
        e("wealth-nations", "The Wealth of Nations", "Adam Smith", LadderStage.MODERN_MIND,
            "Economics before it forgot it was a branch of moral philosophy."),
        e("gibbon", "The Decline and Fall of the Roman Empire", "Edward Gibbon", LadderStage.MODERN_MIND,
            "The greatest history in English, and the best prose on this list."),
        e("federalist", "The Federalist Papers", "Alexander Hamilton", LadderStage.MODERN_MIND,
            "A constitution argued into existence in public, in newspapers, at speed."),
        e("kant", "The Critique of Pure Reason", "Immanuel Kant", LadderStage.MODERN_MIND,
            "The hardest book here. Do not start with it; by now you have the run-up."),
        e("on-liberty", "On Liberty", "John Stuart Mill", LadderStage.MODERN_MIND,
            "Why an argument you are sure is wrong still has to be allowed. Short, and never dated."),
        e("origin-species", "On the Origin of Species", "Charles Darwin", LadderStage.MODERN_MIND,
            "A world-changing argument built entirely out of patient, ordinary observation."),
        e("communist-manifesto", "The Communist Manifesto", "Karl Marx", LadderStage.MODERN_MIND,
            "Forty pages that moved the twentieth century, whatever you conclude about them."),
        e("zarathustra", "Thus Spake Zarathustra", "Friedrich Nietzsche", LadderStage.MODERN_MIND,
            "Philosophy written as scripture by someone demolishing scripture."),
        e("dreams", "The Interpretation of Dreams", "Sigmund Freud", LadderStage.MODERN_MIND,
            "Much of it is wrong. The idea that you are not transparent to yourself stuck."),

        // --- The novel ---
        e("pride-prejudice", "Pride and Prejudice", "Jane Austen", LadderStage.NOVEL,
            "The novel discovering that a private life is enough material for a great one."),
        e("frankenstein", "Frankenstein", "Mary Shelley", LadderStage.NOVEL,
            "Science fiction invented at nineteen, and still the best question the genre asks."),
        e("jane-eyre", "Jane Eyre", "Charlotte Brontë", LadderStage.NOVEL,
            "A first person who refuses to be likeable and wins anyway."),
        e("wuthering", "Wuthering Heights", "Emily Brontë", LadderStage.NOVEL,
            "Love as a destructive natural force, with no one in it to admire."),
        e("moby-dick", "Moby-Dick", "Herman Melville", LadderStage.NOVEL,
            "An adventure novel that keeps stopping to become something much stranger."),
        e("bleak-house", "Bleak House", "Charles Dickens", LadderStage.NOVEL,
            "Dickens at full stretch: a whole society indicted through one lawsuit."),
        e("madame-bovary", "Madame Bovary", "Gustave Flaubert", LadderStage.NOVEL,
            "The sentence-by-sentence standard every novelist after it had to answer."),
        e("middlemarch", "Middlemarch", "George Eliot", LadderStage.NOVEL,
            "The most intelligent novel in English about ordinary people failing to be great."),
        e("war-and-peace", "War and Peace", "Leo Tolstoy", LadderStage.NOVEL,
            "The widest lens ever pointed at a society. Less difficult than its reputation."),
        e("anna-karenina", "Anna Karenina", "Leo Tolstoy", LadderStage.NOVEL,
            "Tolstoy narrower and deeper. Start here if the other one frightens you."),
        e("crime-punishment", "Crime and Punishment", "Fyodor Dostoevsky", LadderStage.NOVEL,
            "A thriller where the crime happens early and the interrogation is of a soul.",
            aliases = listOf("Dostoyevsky")),
        e("karamazov", "The Brothers Karamazov", "Fyodor Dostoevsky", LadderStage.NOVEL,
            "Faith, patricide and the Grand Inquisitor. His last and largest argument.",
            aliases = listOf("Dostoyevsky")),
        e("huck-finn", "Adventures of Huckleberry Finn", "Mark Twain", LadderStage.NOVEL,
            "American prose finds its own voice, and a boy out-argues his whole society."),
        e("portrait-lady", "The Portrait of a Lady", "Henry James", LadderStage.NOVEL,
            "Consciousness rendered so finely that plot almost stops being necessary."),
        e("heart-darkness", "Heart of Darkness", "Joseph Conrad", LadderStage.NOVEL,
            "Empire seen from inside it, by someone who could not quite see out."),
        e("tess", "Tess of the d'Urbervilles", "Thomas Hardy", LadderStage.NOVEL,
            "A novel that puts a society on trial and gives it no way to acquit itself."),
        e("portrait-artist", "A Portrait of the Artist as a Young Man", "James Joyce", LadderStage.NOVEL,
            "Read this before Ulysses. It teaches you how Joyce expects to be read."),
        e("metamorphosis", "The Metamorphosis", "Franz Kafka", LadderStage.NOVEL,
            "The twentieth century's central image, delivered in the first sentence."),
        e("mrs-dalloway", "Mrs Dalloway", "Virginia Woolf", LadderStage.NOVEL,
            "One day, several minds, and the moment the novel stops needing events."),
    )

    private fun e(
        key: String,
        title: String,
        author: String,
        stage: LadderStage,
        note: String,
        aliases: List<String> = emptyList(),
    ) = LadderEntry(key, title, author, stage, note, aliases)
}
