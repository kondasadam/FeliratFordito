package com.example.feliratfordito

import android.content.Context

/** Játékprofilok: háttér + szójegyzék, amit a Gemini minden fordításnál megkap. */
object GameProfiles {

    private val HOGWARTS_LEGACY = """
JÁTÉK: Hogwarts Legacy (Harry Potter világa, 1890-es évek). A játékos ötödéves roxforti diák, aki az ősi mágiát (ancient magic) használja; egy kobold vezette lázadás és sötét varázslók próbálják megszerezni ezt a mágiát.
A személyneveket mindig hagyd eredetiben.
SZÓJEGYZÉK (angol = magyar, ezt kötelező használni):
Hogwarts = Roxfort; Hogsmeade = Roxmorts; Gryffindor = Griffendél; Slytherin = Mardekár; Ravenclaw = Hollóhát; Hufflepuff = Hugrabug;
Muggle = mugli; Quidditch = kviddics; Floo powder = hop-por; Floo Network / Floo Flame = hop-hálózat / hop-tűz; Sorting Hat = Teszlek Süveg;
Forbidden Forest = Tiltott Rengeteg; Great Hall = Nagyterem; Room of Requirement = Szükség Szobája; Hospital Wing = gyengélkedő; common room = klubhelyiség;
Headmaster = igazgató; Head of House = házvezető tanár; Prefect = prefektus; house points = házpontok; professor = professzor;
Defence Against the Dark Arts = Sötét Varázslatok Kivédése; Potions = bájitaltan; Herbology = gyógynövénytan; Charms = bűbájtan; Transfiguration = átváltoztatástan;
Astronomy = asztronómia; Divination = jóslástan; History of Magic = mágiatörténet; Care of Magical Creatures = legendás lények gondozása; Flying = repülés;
Ministry of Magic = Mágiaügyi Minisztérium; Auror = auror; goblin = kobold; house-elf = házimanó; wand = pálca; broom = seprű; Unforgivable Curses = főbenjáró átkok;
Galleon / Sickle / Knut = galleon / sarló / knút; Butterbeer = vajsör; The Three Broomsticks = Három Seprű; The Hog's Head = Szárnyas Vadkan; Honeydukes = Mézesfalás;
Diagon Alley = Abszol út; Hippogriff = hipogriff; Thestral = thesztrál; Mandrake = mandragóra; Dark wizard = sötét varázsló; Dark Arts = sötét varázslatok;
ancient magic = ősi mágia; the Keepers = az Őrzők; repository = tárház; Field Guide = terepkalauz; Merlin Trial = Merlin-próba; Gear = Felszerelés; Talents = Képességek; Collections = Gyűjtemények; Challenges = Kihívások; Quests / Assignments = Küldetések / Feladatok; Owl Mail = baglyposta.
Varázsigék és a játék saját nevei maradjanak eredetiben.
Megszólítás: a diákok egymást tegezik; a tanárokat a diák magázza.
""".trimIndent()

    /** Beépített profil a játék neve alapján (ha van). */
    fun builtin(game: String): String? {
        val g = game.lowercase()
        return when {
            "hogwarts" in g || "roxfort" in g -> HOGWARTS_LEGACY
            else -> null
        }
    }

    /** A fordításhoz használt profil: a saját (szerkesztett) jegyzet, vagy ha az üres, a beépített. */
    fun current(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)
        val notes = prefs.getString("gameNotes", "").orEmpty().trim()
        if (notes.isNotEmpty()) return notes.take(5000)
        return builtin(Gemini.game(ctx)).orEmpty()
    }

    /** Profil készítése a Gemini tudásából bármely játékhoz (egyetlen kérés). */
    fun generate(ctx: Context, game: String): String? {
        val system = buildString {
            append("Egy élő, kamerás játékfelirat-fordító app számára készíts tömör háttérleírást egy videójátékról, magyarul. ")
            append("Formátum: JÁTÉK: (1-2 mondat a játékról és a történet alapjáról)  ")
            append("SZÓJEGYZÉK (angol = magyar): a játék gyakori kifejezései, menüpontjai, tárgyai, címei – személynevek NÉLKÜL, azok eredetiben maradnak. ")
            append("Ha a játéknak vagy a világának van hivatalos magyar fordítása, azokat a neveket használd; ")
            append("a tulajdonneveket és kitalált kifejezéseket, amelyeknek nincs bevett magyar alakja, jelöld úgy, hogy maradjanak eredetiben. ")
            append("Ha valamiben nem vagy biztos, inkább hagyd ki. Legfeljebb 1500 karakter. Csak a profilt írd.")
        }
        return Gemini.generate(ctx, system, "A játék: $game", 1800, 45_000, modelOverride = "gemini-flash-latest")
    }
}
