<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Product;

class CreateProduct
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Product $product): Product
    {
        $this->em->persist($product);
        $this->em->flush();

        return $product;
    }
}
