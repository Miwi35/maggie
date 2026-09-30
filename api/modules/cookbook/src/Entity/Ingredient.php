<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Repository\IngredientRepository;
use Maggie\Cookbook\State\CreateIngredientProcessor;
use Maggie\Cookbook\State\DeleteIngredientProcessor;
use Maggie\Cookbook\State\UpdateIngredientProcessor;
use Maggie\Grocery\Entity\Product;

#[ORM\Entity(repositoryClass: IngredientRepository::class)]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Post(processor: CreateIngredientProcessor::class),
    new Patch(processor: UpdateIngredientProcessor::class),
    new Delete(processor: DeleteIngredientProcessor::class),
])]
class Ingredient extends Product
{
}
