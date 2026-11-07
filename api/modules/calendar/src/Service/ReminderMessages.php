<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\TaskSettings;
use Random\Randomizer;

/**
 * The words of a reminder (MAG-510): written banks, one per tone, never the
 * model. In the familiar « tu », never a reproach — a task late is
 * « reportée ». A draw leaves out the message of the previous reminder of the
 * same kind, so the same one never comes twice in a row.
 *
 * A message is known by its key, `{bank}.{tone}.{n}` counted from 1:
 * `nudge.direct.4`, `routine.doux.1`. `{titre}` is the task's title, `{liste}`
 * the titles of the routines of a moment, separated by commas (MAG-512). The
 * morning alerts had a bank too, gone with them (MAG-718): a reminder at a
 * precise time says its task and its time.
 */
final class ReminderMessages
{
    /** The longest a notification title may be (its column). */
    public const TITLE_MAX = 255;

    /** @var array<string, list<string>> */
    public const NUDGE = [
        TaskSettings::TONE_DOUX => [
            'Et si tu commençais « {titre} » ? Juste 10 minutes.',
            '« {titre} » t\'attend tranquillement. Un petit pas suffit.',
            'Un moment pour « {titre} » ? Rien ne presse, juste le début.',
            'Tu as un peu d\'élan ? « {titre} » est la suivante.',
            'Juste le premier pas de « {titre} », le reste viendra.',
            '« {titre} », 10 minutes, et tu verras où ça en est.',
            'Quand tu veux : « {titre} » est prête.',
            'Petit rappel tout doux : « {titre} ».',
            'On s\'y met ensemble ? « {titre} », juste un peu.',
            '« {titre} » : un tout petit morceau, ça compte déjà.',
        ],
        TaskSettings::TONE_DIRECT => [
            'Suivante : « {titre} ».',
            '« {titre} » — 10 minutes, maintenant ?',
            'C\'est le moment pour « {titre} ».',
            'Prochaine étape : « {titre} ».',
            '« {titre} ». Tu lances le minuteur ?',
            'Au programme maintenant : « {titre} ».',
            '« {titre} » : on démarre ?',
            '10 minutes pour « {titre} », c\'est parti ?',
            'Créneau libre : « {titre} ».',
            'À toi : « {titre} ».',
        ],
        TaskSettings::TONE_DROLE => [
            '« {titre} » fait les cent pas devant ta porte. On lui ouvre ?',
            'Flash spécial : « {titre} » est prête à se faire cocher.',
            '« {titre} » a demandé de tes nouvelles. 10 minutes ?',
            'Le minuteur s\'ennuie. « {titre} », ça lui ferait plaisir.',
            'Petit défi : « {titre} » avant que le café refroidisse ?',
            '« {titre} » attend son heure de gloire. C\'est peut-être maintenant.',
            'Une tâche sauvage apparaît : « {titre} ». Tu la coches ?',
            '« {titre} » parie que tu n\'en feras que 10 minutes. Donne-lui raison.',
            'Psst. « {titre} ». C\'est tout, c\'est le message.',
            '« {titre} » a mis sa plus belle tenue rien que pour toi.',
        ],
    ];

    /**
     * The routines of a moment, grouped in one reminder (MAG-512), by tone.
     *
     * @var array<string, list<string>>
     */
    public const ROUTINE = [
        TaskSettings::TONE_DOUX => [
            '{liste}, si tu en as envie.',
            'Un petit moment pour : {liste}.',
            'Quand tu veux : {liste}.',
            '{liste}. À ton rythme.',
            'C\'est le bon moment pour : {liste}.',
            '{liste}, tranquillement.',
        ],
        TaskSettings::TONE_DIRECT => [
            '{liste}.',
            'À faire maintenant : {liste}.',
            'C\'est le moment : {liste}.',
            'Au programme : {liste}.',
            '{liste} — c\'est parti ?',
            'Routine : {liste}.',
        ],
        TaskSettings::TONE_DROLE => [
            '{liste} t\'attendent en coulisses.',
            'Communiqué du ministère des Petites Habitudes : {liste}.',
            '{liste}. Ton toi de demain te dit merci d\'avance.',
            'Les routines frappent à la porte : {liste}.',
            '{liste} — même pas mal.',
            'Petit spoiler : {liste}, et après c\'est fait.',
        ],
    ];

    private readonly Randomizer $randomizer;

    public function __construct(?Randomizer $randomizer = null)
    {
        $this->randomizer = $randomizer ?? new Randomizer();
    }

    /**
     * A nudge on the task « now », in the tone set.
     *
     * @return array{key: string, message: string}
     *
     * @throws \InvalidArgumentException on an unknown tone
     */
    public function nudge(string $tone, string $title, ?string $previousKey): array
    {
        $messages = self::NUDGE[$tone] ?? throw new \InvalidArgumentException(\sprintf('No reminder messages for the tone "%s".', $tone));

        return $this->draw('nudge.'.$tone, $messages, $previousKey, $title);
    }

    /**
     * The reminder of the routines of a moment, in the tone set: the titles are
     * listed, separated by commas.
     *
     * @param list<string> $titles
     *
     * @return array{key: string, message: string}
     *
     * @throws \InvalidArgumentException on an unknown tone
     */
    public function routine(string $tone, array $titles, ?string $previousKey): array
    {
        $messages = self::ROUTINE[$tone] ?? throw new \InvalidArgumentException(\sprintf('No reminder messages for the tone "%s".', $tone));

        return $this->draw('routine.'.$tone, $messages, $previousKey, implode(', ', $titles), '{liste}');
    }

    /** @return list<string> every message of every bank, placeholders left in */
    public static function all(): array
    {
        return array_merge(...array_values(self::NUDGE), ...array_values(self::ROUTINE));
    }

    /**
     * @param list<string> $messages
     *
     * @return array{key: string, message: string}
     */
    private function draw(string $prefix, array $messages, ?string $previousKey, string $title, string $placeholder = '{titre}'): array
    {
        $keys = [];
        foreach ($messages as $index => $message) {
            $key = $prefix.'.'.($index + 1);
            if ($key !== $previousKey) {
                $keys[$key] = $message;
            }
        }

        $key = array_keys($keys)[$this->randomizer->getInt(0, \count($keys) - 1)];
        $message = $keys[$key];

        // The title is cut, not the words around it: the whole fits a notification title.
        $room = self::TITLE_MAX - mb_strlen(str_replace($placeholder, '', $message));
        $message = str_replace($placeholder, mb_strimwidth($title, 0, max(1, $room), '…'), $message);

        return ['key' => $key, 'message' => $message];
    }
}
