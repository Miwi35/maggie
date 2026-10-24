<?php

namespace Maggie\Cookbook\Tests\Service;

use Maggie\Cookbook\Service\StepTextCitations;
use Maggie\Grocery\Enum\Unit;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class StepTextCitationsTest extends TestCase
{
    /** @return iterable<string, array{string, string, string, string|null}> */
    public static function renames(): iterable
    {
        yield 'in the middle' => ['Faire revenir les lardons fumés', 'Lardons fumés', 'Poitrine fumée', 'Faire revenir les poitrine fumée'];
        yield 'capitalised at the start' => ['Lardons fumés dorés', 'lardons fumés', 'poitrine fumée', 'Poitrine fumée dorés'];
        yield 'every occurrence' => ['Les lardons, puis encore des lardons', 'lardons', 'dés de jambon', 'Les dés de jambon, puis encore des dés de jambon'];
        yield 'not inside another word' => ['Ajouter les lardonnets', 'lardon', 'jambon', null];
        yield 'not cited' => ['Couper le bœuf', 'Lardons fumés', 'Poitrine fumée', null];
        yield 'same name' => ['Les lardons', 'Lardons', 'lardons', null];
        yield 'regex characters in the name' => ['Ajouter la sauce (piquante)', 'sauce (piquante)', 'sauce douce', 'Ajouter la sauce douce'];
    }

    #[DataProvider('renames')]
    public function testRenameIngredient(string $text, string $old, string $new, ?string $expected): void
    {
        self::assertSame($expected, StepTextCitations::renameIngredient($text, $old, $new));
    }

    /** @return iterable<string, array{string, float, Unit, float, Unit, string|null}> */
    public static function requantities(): iterable
    {
        yield 'grams' => ['Faire revenir 200 g de lardons', 200.0, Unit::Gram, 150.0, Unit::Gram, 'Faire revenir 150 g de lardons'];
        yield 'without a space' => ['Ajouter 200g de lardons', 200.0, Unit::Gram, 250.0, Unit::Gram, 'Ajouter 250 g de lardons'];
        yield 'decimals, in French' => ['Verser 1,5 l de vin', 1.5, Unit::Liter, 2.0, Unit::Liter, 'Verser 2 l de vin'];
        yield 'unit change' => ['Verser 500 ml de vin', 500.0, Unit::Milliliter, 0.5, Unit::Liter, 'Verser 0,5 l de vin'];
        yield 'a word unit takes its plural' => ['Ajouter 1 pièce', 1.0, Unit::Piece, 3.0, Unit::Piece, 'Ajouter 3 pièces'];
        yield 'another quantity is left alone' => ['Ajouter 300 g de farine', 200.0, Unit::Gram, 150.0, Unit::Gram, null];
        yield 'a longer number is left alone' => ['Chauffer à 1200 g', 200.0, Unit::Gram, 150.0, Unit::Gram, null];
        yield 'nothing changes' => ['Ajouter 200 g', 200.0, Unit::Gram, 200.0, Unit::Gram, null];
    }

    #[DataProvider('requantities')]
    public function testRequantify(string $text, float $oldQuantity, Unit $oldUnit, float $newQuantity, Unit $newUnit, ?string $expected): void
    {
        self::assertSame($expected, StepTextCitations::requantify($text, $oldQuantity, $oldUnit, $newQuantity, $newUnit));
    }
}
