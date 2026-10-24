<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Grocery\Enum\Unit;

/**
 * What a step's text says about an ingredient line — its name, its quantity —
 * and what it would say about the line that replaces it (MAG-601). Pure: the
 * texts are only ever rewritten when the caller asks for it.
 */
final class StepTextCitations
{
    private const WORD_EDGE_BEFORE = '(?<![\p{L}\p{N}])';
    private const WORD_EDGE_AFTER = '(?![\p{L}\p{N}])';

    /** The text with `$old` replaced by `$new` wherever it is cited by name, or null when it is not cited. */
    public static function renameIngredient(string $text, string $old, string $new): ?string
    {
        if ('' === trim($old) || mb_strtolower(trim($old)) === mb_strtolower(trim($new))) {
            return null;
        }

        $pattern = '/'.self::WORD_EDGE_BEFORE.preg_quote(trim($old), '/').self::WORD_EDGE_AFTER.'/iu';
        $count = 0;
        $result = preg_replace_callback($pattern, static function (array $match) use ($new): string {
            $first = mb_substr($match[0], 0, 1);
            $head = mb_substr($new, 0, 1);

            // « Lardons » at the start of a sentence stays capitalised, « lardons » in the middle stays plain.
            return (mb_strtoupper($first) === $first && mb_strtolower($first) !== $first ? mb_strtoupper($head) : mb_strtolower($head)).mb_substr($new, 1);
        }, $text, -1, $count);

        return $count > 0 && null !== $result ? $result : null;
    }

    /** The text with the old quantity and unit (« 200 g ») swapped for the new ones, or null when it does not cite them. */
    public static function requantify(string $text, float $oldQuantity, Unit $oldUnit, float $newQuantity, Unit $newUnit): ?string
    {
        if (abs($oldQuantity - $newQuantity) < 1e-9 && $oldUnit === $newUnit) {
            return null;
        }

        $pattern = '/'.self::WORD_EDGE_BEFORE.'(?<![.,]\d)'.self::numberPattern($oldQuantity).'\s?'.self::unitPattern($oldUnit).self::WORD_EDGE_AFTER.'/iu';
        $count = 0;
        $result = preg_replace_callback(
            $pattern,
            static fn (): string => self::number($newQuantity).' '.self::unitWord($newUnit, $newQuantity),
            $text,
            -1,
            $count,
        );

        return $count > 0 && null !== $result ? $result : null;
    }

    private static function numberPattern(float $quantity): string
    {
        $text = rtrim(rtrim(number_format($quantity, 3, '.', ''), '0'), '.');

        return str_replace('\\.', '[.,]', preg_quote($text, '/'));
    }

    private static function number(float $quantity): string
    {
        return str_replace('.', ',', rtrim(rtrim(number_format($quantity, 3, '.', ''), '0'), '.'));
    }

    private static function unitPattern(Unit $unit): string
    {
        return '(?:'.match ($unit) {
            Unit::Gram => 'g(?:rammes?)?',
            Unit::Kilogram => 'kg|kilos?',
            Unit::Milliliter => 'ml',
            Unit::Liter => 'l|litres?',
            Unit::Centiliter => 'cl',
            Unit::Piece => 'pi[èe]ces?',
            Unit::Bunch => 'bottes?',
            Unit::Can => 'bo[îi]tes?',
            Unit::Bottle => 'bouteilles?',
            Unit::Pack => 'paquets?',
            Unit::Sachet => 'sachets?',
            Unit::Jar => 'bocal|bocaux',
        }.')';
    }

    private static function unitWord(Unit $unit, float $quantity): string
    {
        $plural = $quantity > 1;

        return match ($unit) {
            Unit::Gram => 'g',
            Unit::Kilogram => 'kg',
            Unit::Milliliter => 'ml',
            Unit::Liter => 'l',
            Unit::Centiliter => 'cl',
            Unit::Piece => $plural ? 'pièces' : 'pièce',
            Unit::Bunch => $plural ? 'bottes' : 'botte',
            Unit::Can => $plural ? 'boîtes' : 'boîte',
            Unit::Bottle => $plural ? 'bouteilles' : 'bouteille',
            Unit::Pack => $plural ? 'paquets' : 'paquet',
            Unit::Sachet => $plural ? 'sachets' : 'sachet',
            Unit::Jar => $plural ? 'bocaux' : 'bocal',
        };
    }
}
