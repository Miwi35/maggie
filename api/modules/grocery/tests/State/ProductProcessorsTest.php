<?php

namespace Maggie\Grocery\Tests\State;

use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Message\CreateProductCommand;
use Maggie\Grocery\Message\UpdateProductCommand;
use Maggie\Grocery\State\CreateProductProcessor;
use Maggie\Grocery\State\UpdateProductProcessor;
use PHPUnit\Framework\TestCase;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The Product the processors receive is rebuilt from the Elasticsearch document,
 * not managed by Doctrine: only what they copy into the command reaches the database.
 * The stores and the shelf life were not copied (MAG-190).
 */
class ProductProcessorsTest extends TestCase
{
    private User $user;
    private Store $halles;
    private Store $bio;
    /** @var list<object> */
    private array $dispatched = [];

    protected function setUp(): void
    {
        $this->user = (new User())->setEmail('courses@example.com')->setGoogleId('google-courses')->setName('Courses');
        $this->halles = (new Store())->setName('Halles du voisin')->setUser($this->user);
        $this->bio = (new Store())->setName('Biocoop')->setUser($this->user);
        $this->dispatched = [];
    }

    private function bus(Product $result): MessageBusInterface
    {
        $bus = $this->createStub(MessageBusInterface::class);
        $bus->method('dispatch')->willReturnCallback(function (object $message) use ($result): Envelope {
            $this->dispatched[] = $message;

            return (new Envelope($message))->with(new HandledStamp($result, 'handler'));
        });

        return $bus;
    }

    private function product(?Store $preferred = null, ?Store $fallback = null, ?int $shelfLife = null): Product
    {
        return (new Product())
            ->setName('Câpres')
            ->setCategory(ProductCategory::Other)
            ->setUser($this->user)
            ->setPreferredStore($preferred)
            ->setFallbackStore($fallback)
            ->setShelfLifeDays($shelfLife);
    }

    private function update(Product $data, ?Product $previous): UpdateProductCommand
    {
        (new UpdateProductProcessor($this->bus($data)))->process($data, new Patch(), [], ['previous_data' => $previous]);

        self::assertInstanceOf(UpdateProductCommand::class, $this->dispatched[0]);

        return $this->dispatched[0];
    }

    public function testUpdateCarriesTheStoresAndTheShelfLife(): void
    {
        $data = $this->product($this->halles, $this->bio, 14);

        $command = $this->update($data, $this->product());

        self::assertSame((string) $this->halles->getId(), $command->preferredStoreId);
        self::assertSame((string) $this->bio->getId(), $command->fallbackStoreId);
        self::assertSame(14, $command->shelfLifeDays);
        self::assertSame([], $command->clearFields);
    }

    public function testUpdateClearsAStoreThatWasSetAndIsNowNull(): void
    {
        $previous = $this->product($this->halles, $this->bio, 30);
        $data = $this->product(null, $this->bio, 30);

        $command = $this->update($data, $previous);

        self::assertNull($command->preferredStoreId);
        self::assertSame(['preferredStore'], $command->clearFields);
        self::assertSame((string) $this->bio->getId(), $command->fallbackStoreId);
    }

    public function testUpdateClearsTheFallbackStoreAndTheShelfLife(): void
    {
        $previous = $this->product($this->halles, $this->bio, 30);
        $data = $this->product($this->halles, null, null);

        $command = $this->update($data, $previous);

        self::assertEqualsCanonicalizing(['fallbackStore', 'shelfLifeDays'], $command->clearFields);
    }

    public function testUpdateLeavesUnsetFieldsAlone(): void
    {
        $command = $this->update($this->product(), $this->product());

        self::assertNull($command->preferredStoreId);
        self::assertNull($command->fallbackStoreId);
        self::assertNull($command->shelfLifeDays);
        self::assertSame([], $command->clearFields);
    }

    public function testCreateCarriesTheStoresAndTheShelfLife(): void
    {
        $security = $this->createStub(Security::class);
        $security->method('getUser')->willReturn($this->user);
        $data = $this->product($this->halles, $this->bio, 365);

        (new CreateProductProcessor($this->bus($data), $security))->process($data, new Post());

        $command = $this->dispatched[0];
        self::assertInstanceOf(CreateProductCommand::class, $command);
        self::assertSame((string) $this->halles->getId(), $command->preferredStoreId);
        self::assertSame((string) $this->bio->getId(), $command->fallbackStoreId);
        self::assertSame(365, $command->shelfLifeDays);
    }
}
