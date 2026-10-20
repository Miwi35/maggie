<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\TaskSettings;
use Random\Randomizer;

/**
 * The words of a reward (MAG-508): written banks, one per tone, and one per
 * surprise for every tone — never the model. In the familiar « tu », never a
 * reproach. A draw leaves out the message of the user's previous reward, so
 * the same one never comes twice in a row.
 *
 * A message is known by its key, `{bank}.{n}` counted from 1: `direct.4`.
 */
final class RewardMessages
{
    /** @var array<string, list<string>> */
    public const TONES = [
        TaskSettings::TONE_DOUX => [
            'Fait. Tu peux souffler un peu.',
            'Une de moins. Ta journée s\'allège.',
            'C\'est fait, et c\'est bien fait.',
            'Joli. Un pas après l\'autre, ça avance.',
            'Voilà, c\'est rangé. Prends une seconde pour le savourer.',
            'Bien joué. Le reste peut attendre ton prochain café.',
            'Une chose de faite, c\'est une chose de moins dans la tête.',
            'Tu l\'as fait. Doucement mais sûrement.',
            'Coché. Ta journée prend forme.',
            'Merci de t\'être accordé ce moment. C\'est fait.',
        ],
        TaskSettings::TONE_DIRECT => [
            'Fait. Suivante ?',
            'Coché. Une de moins.',
            'Réglé.',
            'C\'est fait. Bien.',
            'Terminé. Tu avances.',
            'Une case de cochée. Efficace.',
            'Bouclé.',
            'Fait. Proprement.',
            'Ça, c\'est réglé.',
            'Validé. On continue quand tu veux.',
        ],
        TaskSettings::TONE_DROLE => [
            'Boum. Une tâche de moins dans l\'univers.',
            'Coché ! Quelque part, une liste de choses à faire pleure un peu.',
            'Fait. Tu mérites une petite danse, même discrète.',
            'Mission accomplie. Générique de fin, musique héroïque.',
            'Et hop, rayé de la carte.',
            'Une de moins. La pile tremble.',
            'Fait ! Si ça, ce n\'est pas du talent…',
            'Applaudissements du public (c\'est moi, mais j\'applaudis fort).',
            'Hop, dans la poche. Comme un écureuil avec sa noisette.',
            'Coché. Les tâches restantes commencent à s\'inquiéter.',
        ],
    ];

    /**
     * Every tone. `{total}`: the stars of the month; `{jours}`: the days it waited.
     *
     * @var array<string, list<string>>
     */
    public const SURPRISES = [
        Reward::SURPRISE_FULL_DAY => [
            'Étoile filante : tes 5 du jour sont faites. Tout le reste est du bonus.',
            'Étoile filante : journée bouclée. Profite.',
            'Étoile filante : tout est coché pour aujourd\'hui.',
        ],
        Reward::SURPRISE_MONTH_RECORD => [
            'Étoile filante : {total} tâches ce mois-ci, ton record.',
            'Étoile filante : tu dépasses ton meilleur mois, {total} étoiles.',
            'Étoile filante : nouveau record du mois, {total} et ça continue.',
        ],
        Reward::SURPRISE_WAITED => [
            'Étoile filante : celle-là patientait depuis {jours} jours. C\'est réglé.',
            'Étoile filante : {jours} jours qu\'elle attendait, et la voilà faite.',
            'Étoile filante : une tâche de {jours} jours, rangée pour de bon.',
        ],
    ];

    /**
     * The words of a palier (MAG-509), a chapter finished: one bank per tone,
     * known by the key `palier-{tone}.{n}`.
     *
     * @var array<string, list<string>>
     */
    public const PALIERS = [
        TaskSettings::TONE_DOUX => [
            'Un chapitre entier. Prends le temps de le savourer.',
            'Chapitre bouclé. Regarde tout le chemin fait.',
            'Une belle étape franchie. Ça mérite une vraie pause.',
        ],
        TaskSettings::TONE_DIRECT => [
            'Chapitre terminé.',
            'Chapitre bouclé. Au suivant quand tu veux.',
            'Un chapitre de fait. Solide.',
        ],
        TaskSettings::TONE_DROLE => [
            'Chapitre terminé ! Les trompettes sont en route.',
            'Fin du chapitre. Le public réclame la suite.',
            'Chapitre bouclé. Quelque part, un dragon a perdu.',
        ],
    ];

    private readonly Randomizer $randomizer;

    public function __construct(?Randomizer $randomizer = null)
    {
        $this->randomizer = $randomizer ?? new Randomizer();
    }

    /**
     * A message of the tone's bank, or of the surprise's when there is one.
     *
     * @param array<string, int|string> $values the placeholders, `total` and `jours`
     *
     * @return array{key: string, message: string}
     *
     * @throws \InvalidArgumentException on an unknown tone or surprise
     */
    public function draw(string $tone, ?string $surprise, ?string $previousKey, array $values = []): array
    {
        $bank = $surprise ?? $tone;
        $messages = (null === $surprise ? self::TONES : self::SURPRISES)[$bank]
            ?? throw new \InvalidArgumentException(\sprintf('No reward messages for "%s".', $bank));

        $keys = [];
        foreach (array_keys($messages) as $index) {
            $key = $bank.'.'.($index + 1);
            if ($key !== $previousKey) {
                $keys[$key] = $messages[$index];
            }
        }

        $key = array_keys($keys)[$this->randomizer->getInt(0, \count($keys) - 1)];
        $message = $keys[$key];
        foreach ($values as $name => $value) {
            $message = str_replace('{'.$name.'}', (string) $value, $message);
        }

        return ['key' => $key, 'message' => $message];
    }

    /**
     * A message of the tone's palier bank, never the previous reward's.
     *
     * @return array{key: string, message: string}
     *
     * @throws \InvalidArgumentException on an unknown tone
     */
    public function drawPalier(string $tone, ?string $previousKey): array
    {
        $messages = self::PALIERS[$tone] ?? throw new \InvalidArgumentException(\sprintf('No palier messages for "%s".', $tone));

        $keys = [];
        foreach ($messages as $index => $message) {
            $key = 'palier-'.$tone.'.'.($index + 1);
            if ($key !== $previousKey) {
                $keys[$key] = $message;
            }
        }

        $key = array_keys($keys)[$this->randomizer->getInt(0, \count($keys) - 1)];

        return ['key' => $key, 'message' => $keys[$key]];
    }

    /** @return list<string> every message of every bank, placeholders left in */
    public static function all(): array
    {
        return array_merge(...array_values(self::TONES), ...array_values(self::SURPRISES), ...array_values(self::PALIERS));
    }
}
