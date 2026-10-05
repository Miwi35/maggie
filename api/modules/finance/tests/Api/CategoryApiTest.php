<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class CategoryApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreateCategoryRequiresAuthentication(): void
    {
        $this->loadFixtures('user.yaml');

        $this->client->request('POST', '/api/categories', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode(['name' => 'Alimentation'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateCategoryPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/categories', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Alimentation',
            'obligation' => 'mandatory',
            'color' => '#4CAF50',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Alimentation', $data['name']);
        self::assertSame('mandatory', $data['obligation']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(Category::class)->findAll());

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testCreateSubCategory(): void
    {
        $this->loadFixtures('category.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $parent = $this->getFixture('leisure');

        $this->client->request('POST', '/api/categories', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Cinéma',
            'obligation' => 'optional',
            'parent' => '/api/categories/'.$parent->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Cinéma', $data['name']);
    }

    public function testCreateCategoryValidationError(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/categories', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // Missing required 'name'
            'obligation' => 'optional',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testAnIncomeCategoryCanBeDeclaredARente(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/categories', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Loyers perçus',
            'obligation' => 'income',
            'passiveIncome' => true,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['passiveIncome']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $categories = $em->getRepository(Category::class)->findAll();
        self::assertTrue($categories[0]->isPassiveIncome());

        $this->assertMercureUpdatePublished('/categories/');

        // The whole point of the property's name: one spelling on both
        // channels. `isPassiveIncome` here and `passiveIncome` above is the
        // disagreement `isCushion` already pays for.
        $payload = json_decode(
            $this->getMercureHub()->getUpdates()[0]->getData(),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
        self::assertTrue($payload['passiveIncome']);
        self::assertArrayNotHasKey('isPassiveIncome', $payload);
    }

    /**
     * A rente is money coming in. Flagged on a spending category it would feed
     * the independence counter a number it reads as income.
     */
    public function testASpendingCategoryCannotBeARente(): void
    {
        $this->loadFixtures('user.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/categories', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'name' => 'Loisirs',
            'obligation' => 'optional',
            'passiveIncome' => true,
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testTheRenteFlagCanBePostedAndTakenBack(): void
    {
        $this->loadFixtures('category_income.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $rent = $this->getFixture('rent_income');

        $this->patch((string) $rent->getId(), ['passiveIncome' => false]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertFalse($em->find(Category::class, $rent->getId())->isPassiveIncome());
    }

    /** Moving a rente to a spending obligation is the same contradiction. */
    public function testARenteCannotBeMovedToASpendingObligation(): void
    {
        $this->loadFixtures('category_income.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $rent = $this->getFixture('rent_income');

        $this->patch((string) $rent->getId(), ['obligation' => 'optional']);

        self::assertResponseStatusCodeSame(422);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $id, array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('PATCH', '/api/categories/'.$id, [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testPatchCategoryRequiresAuthentication(): void
    {
        $this->loadFixtures('category_with_parent.yaml');
        $concerts = $this->getFixture('concerts');

        $this->patch((string) $concerts->getId(), ['parent' => null], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullParentMakesTheCategoryTopLevel(): void
    {
        $this->loadFixtures('category_with_parent.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $concerts = $this->getFixture('concerts');

        $this->patch((string) $concerts->getId(), ['parent' => null]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Category::class, $concerts->getId());
        self::assertNull($refreshed->getParent());
        self::assertSame('Concerts', $refreshed->getName());
        self::assertSame('#9C27B0', $refreshed->getColor());

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchIndexDispatched(Category::class);
    }

    public function testPatchWithNullColorAndIconClearsThemAndKeepsTheParent(): void
    {
        $this->loadFixtures('category_with_parent.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $concerts = $this->getFixture('concerts');

        $this->patch((string) $concerts->getId(), ['color' => null, 'icon' => null]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Category::class, $concerts->getId());
        self::assertNull($refreshed->getColor());
        self::assertNull($refreshed->getIcon());
        self::assertSame('Loisirs', $refreshed->getParent()?->getName());
    }

    public function testDeleteCategoryRemovesAndPublishes(): void
    {
        $this->loadFixtures('category.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $category = $this->getFixture('food');
        $categoryId = $category->getId();

        $this->client->request('DELETE', '/api/categories/'.$categoryId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Category::class, $categoryId));

        $this->assertMercureUpdatePublished('/categories/');
        $this->assertElasticsearchDeleteDispatched('categories');
    }
}
