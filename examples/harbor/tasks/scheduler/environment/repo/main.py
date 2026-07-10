import argparse

from store import Store


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("database")
    parser.add_argument("--now", type=int, required=True)
    args = parser.parse_args()
    print(Store(args.database).claim(args.now))


if __name__ == "__main__":
    main()
