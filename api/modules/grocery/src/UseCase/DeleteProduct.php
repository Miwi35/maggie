<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Maggie\Grocery\Entity\Product;
use Doctrine\ORM\EntityManagerInterface;

class DeleteProduct
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Product $product): void
    {
        $this->em->remove($product);
        $this->em->flush();
    }
}
