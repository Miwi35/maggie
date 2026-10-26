<?php

namespace Maggie\Grocery\Tests\Entity;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\ProductCategory;
use PHPUnit\Framework\TestCase;

/** MAG-647: the weight of one piece reaches the open screens through Mercure, not only on a refetch. */
class ProductPieceWeightPayloadTest extends TestCase
{
    public function testThePieceWeightTravelsInTheMercurePayload(): void
    {
        $product = (new Product())->setName('Oignon')->setCategory(ProductCategory::Produce)->setPieceWeightGrams(100.0);

        self::assertSame(100.0, $product->toMercurePayload()['pieceWeightGrams']);
        self::assertSame(100.0, $product->toMercurePayload(['pieceWeightGrams'])['pieceWeightGrams']);
    }
}
