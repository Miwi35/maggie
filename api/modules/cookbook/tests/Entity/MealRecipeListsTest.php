<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Entity;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Enum\MealSlot;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

/**
 * Moving a meal rewrites its recipes (`UpdateMealHandler` removes them and adds
 * them back), which leaves the collection's keys with holes. Serialised as it
 * stands, `recipeIds` becomes a JSON object (`{"1": "…"}`): Elasticsearch
 * refuses the document, the index keeps the meal where it was, and the week
 * view draws it back in its old cell (MAG-250).
 */
final class MealRecipeListsTest extends TestCase
{
    private function aMealWithRecipesRewritten(): Meal
    {
        $user = new User();
        $user->setEmail('repas@example.com');
        $user->setGoogleId('google-repas');
        $user->setName('Repas');

        $agenda = new Agenda();
        $agenda->setName('Repas');
        $agenda->setUser($user);

        $meal = new Meal();
        $meal->setAgenda($agenda);
        $meal->setSlot(MealSlot::Lunch);
        $meal->setSummary('Déjeuner');
        $meal->setDate(new \DateTimeImmutable('2026-10-07'));

        $first = new Recipe();
        $first->setName('Cassoulet');
        $second = new Recipe();
        $second->setName('Gratin');

        $meal->addRecipe($first);
        $meal->addRecipe($second);
        $meal->removeRecipe($first);

        return $meal;
    }

    public function testTheSearchDocumentListsTheRecipeIdsAsAList(): void
    {
        $document = $this->aMealWithRecipesRewritten()->toSearchDocument();

        self::assertCount(1, $document['recipeIds']);
        self::assertTrue(array_is_list($document['recipeIds']), 'recipeIds must serialise as a JSON array');
    }

    public function testTheMercurePayloadListsTheRecipesAsAList(): void
    {
        $payload = $this->aMealWithRecipesRewritten()->toMercurePayload();

        self::assertCount(1, $payload['recipes']);
        self::assertTrue(array_is_list($payload['recipes']), 'recipes must serialise as a JSON array');
    }
}
