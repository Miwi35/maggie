<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Exception\ProductInUseException;
use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/** A module that holds a hard reference to products vetoes their deletion here. */
#[AutoconfigureTag(ProductDeletionGuard::TAG)]
interface ProductDeletionGuard
{
    public const TAG = 'maggie.grocery.product_deletion_guard';

    /** @throws ProductInUseException */
    public function assertDeletable(Product $product): void;
}
