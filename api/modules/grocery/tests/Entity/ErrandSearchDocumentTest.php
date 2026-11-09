<?php

namespace Maggie\Grocery\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Entity\Store;
use PHPUnit\Framework\TestCase;

/** `/api/errands` is read from Elasticsearch: a field left out of the document reads as null (MAG-765). */
class ErrandSearchDocumentTest extends TestCase
{
    public function testThePaidAmountSurvivesTheRoundTripThroughElasticsearch(): void
    {
        $user = (new User())->setEmail('courses@example.com')->setGoogleId('google-courses')->setName('Courses');
        $store = (new Store())->setName('Halles du voisin')->setUser($user);
        $errand = (new Errand())->setUser($user)->setStore($store)->setPaidAmountCents(4280);

        $em = $this->createStub(EntityManagerInterface::class);
        $em->method('getReference')->willReturnCallback(
            static fn (string $class, mixed $id): ?object => User::class === $class ? $user : $store,
        );
        $source = json_decode(json_encode($errand->toSearchDocument(), JSON_THROW_ON_ERROR), true, 512, JSON_THROW_ON_ERROR);
        $source['id'] = (string) $errand->getId();

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate($source, Errand::class);

        self::assertInstanceOf(Errand::class, $hydrated);
        self::assertSame(4280, $hydrated->getPaidAmountCents());
    }
}
