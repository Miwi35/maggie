<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Mcp\Tool\ListPlacesTool;
use Maggie\Calendar\Mcp\Tool\SavePlaceTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** Known places, Maggie's tools (MAG-652): save_place and list_places, through the bus. */
class PlaceToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('PlaceToolsTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loginFixtureUser();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @param array<string, mixed> $arguments @return array<string, mixed> */
    private function save(array $arguments): array
    {
        return json_decode((self::getContainer()->get(SavePlaceTool::class))(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function list(): array
    {
        return json_decode((self::getContainer()->get(ListPlacesTool::class))(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function stored(string $id): Place
    {
        $this->em()->clear();
        $place = $this->em()->find(Place::class, $id);
        self::assertNotNull($place);

        return $place;
    }

    private function countPlaces(): int
    {
        $this->em()->clear();

        return $this->em()->getRepository(Place::class)->count([]);
    }

    public function testWithoutAUserBothToolsRefuse(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        self::assertSame(MissingMcpUserException::MESSAGE, $this->list()['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $this->save(['name' => 'Dentiste', 'address' => '5 allée du Sourire'])['error']);
        self::assertSame(3, $this->countPlaces());
        $this->assertMercureUpdateCount(0);
    }

    public function testSaveCreatesThePlaceOfTheUserAndPublishesIt(): void
    {
        $data = $this->save([
            'name' => 'Dentiste',
            'address' => '5 allée du Sourire Fictif, 35000 Rennes',
            'latitude' => 48.118,
            'longitude' => -1.668,
            'parkingAddress' => 'Parking du Square Inventé',
            'parkingLatitude' => 48.119,
            'parkingLongitude' => -1.6672,
        ]);

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame('Dentiste', $data['place']['name']);
        $place = $this->stored($data['place']['id']);
        self::assertSame((string) $this->getFixture('test_user')->getId(), (string) $place->getUser()->getId());
        self::assertSame('5 allée du Sourire Fictif, 35000 Rennes', $place->getAddress());
        self::assertSame([48.118, -1.668], [$place->getLatitude(), $place->getLongitude()]);
        self::assertSame('Parking du Square Inventé', $place->getParkingAddress());
        self::assertSame([48.119, -1.6672], [$place->getParkingLatitude(), $place->getParkingLongitude()]);
        self::assertFalse($place->isHome());

        $this->assertMercurePublishedOnce('/api/places/'.$data['place']['id']);
        $this->assertNoElasticsearchIndexDispatched(Place::class);
    }

    public function testSaveWithAnIdChangesThePlaceWithoutCreatingOneAndPublishesIt(): void
    {
        $bakery = $this->id('bakery');

        $data = $this->save(['id' => $bakery, 'name' => 'Boulangerie Dupain', 'address' => '1 place du Pain Fictif, 35000 Rennes', 'latitude' => 48.1, 'longitude' => -1.6]);

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame(3, $this->countPlaces());
        $place = $this->stored($bakery);
        self::assertSame('Boulangerie Dupain', $place->getName());
        self::assertSame([48.1, -1.6], [$place->getLatitude(), $place->getLongitude()]);
        $this->assertMercurePublishedOnce('/api/places/'.$bakery);
    }

    public function testANewAddressWithoutCoordinatesDropsTheOldOnes(): void
    {
        $home = $this->id('home');

        $kept = $this->save(['id' => $home, 'name' => 'La maison', 'address' => '12 rue des Glycines Imaginaires, 35000 Rennes']);
        self::assertSame([48.11, -1.68], [$kept['place']['latitude'], $kept['place']['longitude']]);
        self::assertTrue($kept['place']['isHome'], 'isHome left out keeps its value');

        $this->save(['id' => $home, 'name' => 'La maison', 'address' => '8 rue Neuve Fictive, 35000 Rennes']);
        self::assertFalse($this->stored($home)->hasCoordinates());
    }

    public function testAnEmptyParkingAddressRemovesTheParking(): void
    {
        $bakery = $this->id('bakery');
        $this->save(['id' => $bakery, 'name' => 'Boulangerie', 'address' => '1 place du Pain Fictif', 'parkingAddress' => 'Parking Fictif', 'parkingLatitude' => 48.1, 'parkingLongitude' => -1.6]);

        $this->save(['id' => $bakery, 'name' => 'Boulangerie', 'address' => '1 place du Pain Fictif', 'parkingAddress' => '']);

        $place = $this->stored($bakery);
        self::assertNull($place->getParkingAddress());
        self::assertNull($place->getParkingLatitude());
    }

    public function testTwoHomesInARowLeaveOneHome(): void
    {
        $data = $this->save(['name' => 'Nouvelle maison', 'address' => '8 rue Neuve Fictive, 35000 Rennes', 'isHome' => true]);
        self::assertTrue($data['success']);
        $this->resetMercure();
        $this->save(['id' => $this->id('bakery'), 'name' => 'Boulangerie', 'address' => '1 place du Pain Fictif', 'isHome' => true]);

        $this->em()->clear();
        $homes = $this->em()->getRepository(Place::class)->findBy(['isHome' => true, 'user' => $this->getFixture('test_user')]);
        self::assertSame([$this->id('bakery')], array_map(static fn (Place $place): string => (string) $place->getId(), $homes));
        // The other user's home is theirs.
        self::assertTrue($this->stored($this->id('other_home'))->isHome());
        // The old home is told it is no longer one.
        $this->assertMercureUpdatePublished('/api/places/'.$data['place']['id']);
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function refusals(): iterable
    {
        yield 'no name' => [['address' => '1 rue Fictive'], 'name'];
        yield 'blank name' => [['name' => '  ', 'address' => '1 rue Fictive'], 'name'];
        yield 'no address' => [['name' => 'Dentiste'], 'address'];
        yield 'latitude out of bounds' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'latitude' => 91.0, 'longitude' => 2.0], 'latitude'];
        yield 'longitude out of bounds' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'latitude' => 48.0, 'longitude' => -181.0], 'longitude'];
        yield 'latitude alone' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'latitude' => 48.0], 'longitude'];
        yield 'longitude alone' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'longitude' => 2.0], 'latitude'];
        yield 'parking latitude alone' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'parkingAddress' => 'Parking', 'parkingLatitude' => 48.0], 'parkingLongitude'];
        yield 'parking latitude out of bounds' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'parkingAddress' => 'Parking', 'parkingLatitude' => -90.5, 'parkingLongitude' => 2.0], 'parkingLatitude'];
        yield 'parking coordinates without its address' => [['name' => 'Dentiste', 'address' => '1 rue Fictive', 'parkingLatitude' => 48.0, 'parkingLongitude' => 2.0], 'parkingAddress'];
        yield 'unknown id' => [['id' => '01JAAAAAAAAAAAAAAAAAAAAAAA', 'name' => 'Dentiste', 'address' => '1 rue Fictive'], 'id'];
        yield 'not an id' => [['id' => 'dentiste', 'name' => 'Dentiste', 'address' => '1 rue Fictive'], 'id'];
    }

    /** @param array<string, mixed> $arguments */
    #[DataProvider('refusals')]
    public function testARefusedSaveNamesTheFieldAndChangesNothing(array $arguments, string $field): void
    {
        $data = $this->save($arguments);

        self::assertArrayNotHasKey('success', $data);
        self::assertSame($field, $data['field'], $data['error']);
        self::assertStringStartsWith($field.':', $data['error']);
        self::assertSame(3, $this->countPlaces());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherUsersPlaceIsAnUnknownId(): void
    {
        $other = $this->id('other_home');

        $data = $this->save(['id' => $other, 'name' => 'Volée', 'address' => '1 rue Fictive']);

        self::assertSame('id', $data['field']);
        self::assertSame('Chez moi', $this->stored($other)->getName());
    }

    public function testListReadsTheUsersPlacesHomeFirst(): void
    {
        $this->save(['name' => 'Agence', 'address' => '2 rue Fictive', 'parkingAddress' => 'Parking Fictif']);

        $places = $this->list()['places'];

        self::assertSame(['Maison', 'Agence', 'Boulangerie'], array_column($places, 'name'));
        self::assertTrue($places[0]['isHome']);
        self::assertSame('Parking Fictif', $places[1]['parkingAddress']);
        self::assertSame(
            ['id', 'name', 'address', 'latitude', 'longitude', 'parkingAddress', 'parkingLatitude', 'parkingLongitude', 'isHome'],
            array_keys($places[0]),
        );
    }
}
