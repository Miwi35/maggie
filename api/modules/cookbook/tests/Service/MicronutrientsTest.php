<?php

namespace Maggie\Cookbook\Tests\Service;

use Maggie\Cookbook\Service\Micronutrients;
use PHPUnit\Framework\TestCase;

/** MAG-648: the 23 micronutrients of the recipe sheet and the references a serving is a share of. */
class MicronutrientsTest extends TestCase
{
    public function testTheSheetListsTwentyThreeMicronutrients(): void
    {
        self::assertCount(23, Micronutrients::keys());
    }

    public function testTheAnnexXiiiReferencesGiveTheExpectedShares(): void
    {
        self::assertSame(80.0, Micronutrients::reference('vitaminC'));
        self::assertSame(14.0, Micronutrients::reference('iron'));
        self::assertSame(800.0, Micronutrients::reference('calcium'));

        self::assertSame(50, Micronutrients::percentOfReference('vitaminC', 40.0));
        self::assertSame(100, Micronutrients::percentOfReference('iron', 14.0));
        self::assertSame(25, Micronutrients::percentOfReference('calcium', 200.0));
    }

    public function testTheFattyAcidsFollowAnses(): void
    {
        self::assertEqualsWithDelta(2.22, Micronutrients::reference('omega3Ala'), 0.01, '1 % of 2 000 kcal at 9 kcal/g');
        self::assertEqualsWithDelta(8.89, Micronutrients::reference('omega6'), 0.01, '4 % of 2 000 kcal at 9 kcal/g');
        self::assertSame(0.25, Micronutrients::reference('omega3Dha'));
        self::assertSame(0.25, Micronutrients::reference('omega3Epa'));
    }

    public function testEveryMicronutrientOfTheSheetHasAReference(): void
    {
        foreach (Micronutrients::keys() as $key) {
            self::assertNotNull(Micronutrients::reference($key), $key);
        }
    }

    public function testANutrientWithoutReferenceHasNoShare(): void
    {
        self::assertNull(Micronutrients::reference('cholesterol'));
        self::assertNull(Micronutrients::percentOfReference('cholesterol', 120.0));
    }

    public function testCiqualValuesAreReadByCodeAndAnUnknownValueIsLeftOut(): void
    {
        $values = Micronutrients::fromFood(['nutrients' => [
            ['const_code' => '55100', 'value' => 53.0],
            ['const_code' => '10260', 'value' => null],
            ['const_code' => '41833', 'value' => 0.02],
            ['const_code' => '328', 'value' => 47.0],
        ]]);

        self::assertSame(['vitaminC' => 53.0, 'omega3Ala' => 0.02], $values);
    }
}
