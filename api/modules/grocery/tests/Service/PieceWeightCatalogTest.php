<?php

namespace Maggie\Grocery\Tests\Service;

use Maggie\Grocery\Service\PieceWeightCatalog;
use PHPUnit\Framework\TestCase;

/** MAG-647: the average weight of one piece, the fallback of a product with no weight of its own. */
class PieceWeightCatalogTest extends TestCase
{
    public function testCommonIngredientsHaveTheirAverageWeight(): void
    {
        $catalog = new PieceWeightCatalog();

        self::assertSame(100.0, $catalog->gramsFor('Oignon'));
        self::assertSame(5.0, $catalog->gramsFor('gousse d\'ail'));
        self::assertSame(125.0, $catalog->gramsFor('Carottes'));
        self::assertSame(10.0, $catalog->gramsFor('cube de bouillon'));
    }

    public function testACiqualNameIsReadByWhatComesBeforeTheFirstComma(): void
    {
        self::assertSame(100.0, (new PieceWeightCatalog())->gramsFor('Oignon, cru'));
    }

    public function testAnUnknownIngredientHasNoWeight(): void
    {
        self::assertNull((new PieceWeightCatalog())->gramsFor('Bœuf à braiser'));
    }
}
