package tv.blofy.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BlofyModels {
    private BlofyModels() {}

    static String string(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return "";
        return object.optString(key, "");
    }

    static String first(JSONObject object, String... keys) {
        if (object == null || keys == null) return "";
        for (String key : keys) {
            String value = string(object, key).trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    static final class License {
        final String plan;
        final String status;
        final long expiresAt;
        final int remainingDays;
        final String activationUrl;

        License(JSONObject data) {
            plan = string(data, "plan");
            status = string(data, "status");
            expiresAt = data == null ? 0 : data.optLong("expiresAt", 0);
            remainingDays = data == null ? 0 : data.optInt("remainingDays", 0);
            activationUrl = string(data, "activationUrl");
        }

        boolean usable() { return "trial".equals(plan) || "active".equals(plan); }
    }

    static final class Session {
        final boolean present;
        final String kind;
        final String name;
        final String serverName;
        final JSONObject account;

        Session(JSONObject response) {
            JSONObject data = response == null ? null : response.optJSONObject("session");
            present = data != null;
            kind = string(data, "kind");
            name = string(data, "name");
            serverName = string(data, "serverName");
            account = data == null ? null : data.optJSONObject("account");
        }
    }

    static final class Category {
        final String id;
        final String name;
        final String type;

        Category(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        static List<Category> list(JSONObject response, String type) {
            List<Category> result = new ArrayList<>();
            JSONArray rows = response == null ? null : response.optJSONArray("categories");
            if (rows == null) return result;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null) result.add(new Category(string(row, "id"), string(row, "name"), type));
            }
            return result;
        }
    }

    static final class Media {
        final String id;
        final String name;
        final String image;
        final String backdrop;
        final String categoryId;
        final String rating;
        final String year;
        final String extension;
        final String type;
        final String releaseDate;
        final String ratingSource;
        final String updatedAt;

        Media(String id, String name, String image, String backdrop, String categoryId,
              String rating, String year, String extension, String type) {
            this(id, name, image, backdrop, categoryId, rating, year, extension, type,
                    "", "", "");
        }

        Media(String id, String name, String image, String backdrop, String categoryId,
              String rating, String year, String extension, String type,
              String releaseDate, String ratingSource, String updatedAt) {
            this.id = id;
            this.name = name;
            this.image = ArtworkUrlPolicy.sanitize(image);
            this.backdrop = ArtworkUrlPolicy.sanitize(backdrop);
            this.categoryId = categoryId;
            this.rating = rating;
            this.year = year;
            this.extension = extension;
            this.type = type;
            this.releaseDate = releaseDate == null ? "" : releaseDate;
            this.ratingSource = ratingSource == null ? "" : ratingSource;
            this.updatedAt = updatedAt == null ? "" : updatedAt;
        }

        static Media from(JSONObject row, String fallbackType) {
            return new Media(
                    string(row, "id"), string(row, "name"), string(row, "image"),
                    string(row, "backdrop"), string(row, "categoryId"), string(row, "rating"),
                    string(row, "year"), string(row, "extension"),
                    string(row, "type").isEmpty() ? fallbackType : string(row, "type"),
                    first(row, "releaseDate", "release_date", "airDate", "air_date", "lastAirDate", "last_air_date"),
                    first(row, "ratingSource", "rating_source", "voteSource", "vote_source"),
                    first(row, "updatedAt", "updated_at", "addedAt", "added_at", "dateAdded", "date_added", "added"));
        }

        JSONObject json() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id).put("name", name).put("image", image).put("backdrop", backdrop)
                        .put("categoryId", categoryId).put("rating", rating).put("year", year)
                        .put("extension", extension).put("type", type)
                        .put("releaseDate", releaseDate).put("ratingSource", ratingSource)
                        .put("updatedAt", updatedAt);
            } catch (Exception ignored) {}
            return value;
        }

        /** Small navigation payload: catalog IDs and labels only, never provider URLs. */
        JSONObject navigationJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id).put("name", name)
                        .put("categoryId", categoryId).put("rating", rating).put("year", year)
                        .put("extension", extension).put("type", type)
                        .put("releaseDate", releaseDate).put("ratingSource", ratingSource)
                        .put("updatedAt", updatedAt);
            } catch (Exception ignored) {}
            return value;
        }

        static List<Media> list(JSONObject response, String type) {
            List<Media> result = new ArrayList<>();
            JSONArray rows = response == null ? null : response.optJSONArray("items");
            if (rows == null) return result;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null) result.add(from(row, type));
            }
            return result;
        }
    }

    static final class Episode {
        final String id;
        final int number;
        final String title;
        final String extension;
        final String duration;
        final String image;
        final String airDate;

        Episode(JSONObject row) {
            id = string(row, "id");
            number = row == null ? 0 : row.optInt("number", 0);
            title = string(row, "title");
            extension = string(row, "extension");
            duration = string(row, "duration");
            image = ArtworkUrlPolicy.sanitize(string(row, "image"));
            airDate = first(row, "airDate", "air_date", "releaseDate", "release_date", "date");
        }
    }

    static final class Actor {
        final String name;
        final String character;
        final String image;

        Actor(String name, String character, String image) {
            this.name = name == null ? "" : name;
            this.character = character == null ? "" : character;
            this.image = ArtworkUrlPolicy.sanitize(image);
        }
    }

    static final class Rating {
        final String source;
        final String value;

        Rating(String source, String value) {
            this.source = source == null ? "" : source;
            this.value = value == null ? "" : value;
        }
    }

    static final class Season {
        final String number;
        final List<Episode> episodes = new ArrayList<>();

        Season(JSONObject row) {
            number = first(row, "season", "seasonNumber", "season_number", "number");
            JSONArray values = row == null ? null : row.optJSONArray("episodes");
            if (values != null) for (int index = 0; index < values.length(); index++) {
                JSONObject episode = values.optJSONObject(index);
                if (episode != null) episodes.add(new Episode(episode));
            }
            Collections.sort(episodes, (left, right) -> {
                int leftNumber = left.number > 0 ? left.number : Integer.MAX_VALUE;
                int rightNumber = right.number > 0 ? right.number : Integer.MAX_VALUE;
                int byNumber = Integer.compare(leftNumber, rightNumber);
                if (byNumber != 0) return byNumber;
                return left.title.compareToIgnoreCase(right.title);
            });
        }
    }

    static final class Detail {
        final String id;
        final String name;
        final String description;
        final String image;
        final String backdrop;
        final String rating;
        final String year;
        final String duration;
        final String genre;
        final String extension;
        final String type;
        final String releaseDate;
        final String ratingSource;
        final String updatedAt;
        final String director;
        final List<Season> seasons = new ArrayList<>();
        final List<Actor> cast = new ArrayList<>();
        final List<Actor> crew = new ArrayList<>();
        final List<Rating> ratings = new ArrayList<>();

        Detail(JSONObject data, String fallbackType) {
            id = string(data, "id");
            name = string(data, "name");
            description = string(data, "description");
            image = ArtworkUrlPolicy.sanitize(string(data, "image"));
            backdrop = ArtworkUrlPolicy.sanitize(string(data, "backdrop"));
            rating = string(data, "rating");
            year = string(data, "year");
            duration = string(data, "duration");
            genre = string(data, "genre");
            extension = string(data, "extension");
            String readType = string(data, "type");
            type = readType.isEmpty() ? fallbackType : readType;
            releaseDate = first(data, "releaseDate", "release_date", "airDate", "air_date", "lastAirDate", "last_air_date");
            ratingSource = first(data, "ratingSource", "rating_source", "voteSource", "vote_source");
            updatedAt = first(data, "updatedAt", "updated_at", "addedAt", "added_at", "dateAdded", "date_added", "added");
            director = first(data, "director", "directors", "creator", "created_by");
            JSONArray values = data == null ? null : data.optJSONArray("seasons");
            if (values != null) for (int index = 0; index < values.length(); index++) {
                JSONObject season = values.optJSONObject(index);
                if (season != null) seasons.add(new Season(season));
            }
            Collections.sort(seasons, (left, right) -> {
                int leftNumber = naturalNumber(left.number);
                int rightNumber = naturalNumber(right.number);
                int byNumber = Integer.compare(leftNumber, rightNumber);
                return byNumber != 0 ? byNumber : left.number.compareToIgnoreCase(right.number);
            });
            parseCast(data, cast);
            parseCrew(data, director, crew);
            parseRatings(data, rating, ratingSource, ratings);
        }
    }

    private static void parseCast(JSONObject data, List<Actor> result) {
        if (data == null) return;
        JSONArray rows = data.optJSONArray("cast");
        if (rows == null) rows = data.optJSONArray("actors");
        JSONObject credits = data.optJSONObject("credits");
        if (rows == null && credits != null) rows = credits.optJSONArray("cast");
        Set<String> seen = new LinkedHashSet<>();
        if (rows != null) {
            for (int index = 0; index < rows.length() && result.size() < 24; index++) {
                Object value = rows.opt(index);
                String name;
                String character = "";
                String image = "";
                if (value instanceof JSONObject) {
                    JSONObject actor = (JSONObject) value;
                    name = first(actor, "name", "original_name", "actor", "title");
                    character = first(actor, "character", "role", "known_for_department");
                    image = first(actor, "image", "profile", "profilePath", "profile_path", "photo");
                } else {
                    name = value == null ? "" : String.valueOf(value).trim();
                }
                String key = name.toLowerCase(Locale.US);
                if (!name.isEmpty() && seen.add(key)) result.add(new Actor(name, character, image));
            }
        }

        if (!result.isEmpty()) return;
        String flat = "";
        String[] flatKeys = {"castText", "cast_text", "actorsText", "actors_text", "cast", "actors"};
        for (String key : flatKeys) {
            Object raw = data.opt(key);
            if (raw instanceof String && !((String) raw).trim().isEmpty()) {
                flat = ((String) raw).trim();
                break;
            }
        }
        if (flat.isEmpty()) return;
        for (String name : flat.split("[,،|]")) {
            String clean = name.trim();
            if (!clean.isEmpty() && result.size() < 24) result.add(new Actor(clean, "", ""));
        }
    }

    private static void parseCrew(JSONObject data, String director, List<Actor> result) {
        if (data == null) return;
        JSONArray rows = data.optJSONArray("crew");
        JSONObject credits = data.optJSONObject("credits");
        if (rows == null && credits != null) rows = credits.optJSONArray("crew");
        Set<String> seen = new LinkedHashSet<>();
        if (rows != null) {
            for (int index = 0; index < rows.length() && result.size() < 24; index++) {
                Object value = rows.opt(index);
                String name;
                String role = "";
                String image = "";
                if (value instanceof JSONObject) {
                    JSONObject person = (JSONObject) value;
                    name = first(person, "name", "original_name", "person", "title");
                    role = first(person, "job", "role", "department", "known_for_department");
                    image = first(person, "image", "profile", "profilePath", "profile_path", "photo");
                } else {
                    name = value == null ? "" : String.valueOf(value).trim();
                }
                String key = name.toLowerCase(Locale.US);
                if (!name.isEmpty() && seen.add(key)) result.add(new Actor(name, role, image));
            }
        }

        parseCrewGroup(data, "directors", "إخراج", result, seen);
        parseCrewGroup(data, "writers", "كتابة", result, seen);
        parseCrewGroup(data, "producers", "إنتاج", result, seen);
        parseCrewGroup(data, "creators", "ابتكار", result, seen);

        Object rawCrew = data.opt("crew");
        if (result.isEmpty() && rawCrew instanceof String) {
            for (String raw : ((String) rawCrew).split("[,،|]")) {
                String name = raw.trim();
                String key = name.toLowerCase(Locale.US);
                if (!name.isEmpty() && seen.add(key) && result.size() < 24) {
                    result.add(new Actor(name, "", ""));
                }
            }
        }

        if (director == null || director.trim().isEmpty()) return;
        for (String raw : director.split("[,،|]")) {
            String name = raw.trim();
            String key = name.toLowerCase(Locale.US);
            if (!name.isEmpty() && seen.add(key) && result.size() < 24) {
                result.add(new Actor(name, "إخراج", ""));
            }
        }
    }

    private static void parseCrewGroup(JSONObject data, String key, String fallbackRole,
                                       List<Actor> result, Set<String> seen) {
        if (data == null || result.size() >= 24) return;
        Object raw = data.opt(key);
        if (raw instanceof JSONArray) {
            JSONArray rows = (JSONArray) raw;
            for (int index = 0; index < rows.length() && result.size() < 24; index++) {
                Object value = rows.opt(index);
                String name;
                String role = fallbackRole;
                String image = "";
                if (value instanceof JSONObject) {
                    JSONObject person = (JSONObject) value;
                    name = first(person, "name", "original_name", "person", "title");
                    String suppliedRole = first(person, "job", "role", "department");
                    if (!suppliedRole.isEmpty()) role = suppliedRole;
                    image = first(person, "image", "profile", "profilePath", "profile_path", "photo");
                } else {
                    name = value == null ? "" : String.valueOf(value).trim();
                }
                String seenKey = name.toLowerCase(Locale.US);
                if (!name.isEmpty() && seen.add(seenKey)) result.add(new Actor(name, role, image));
            }
        } else if (raw instanceof String) {
            for (String value : ((String) raw).split("[,،|]")) {
                String name = value.trim();
                String seenKey = name.toLowerCase(Locale.US);
                if (!name.isEmpty() && seen.add(seenKey) && result.size() < 24) {
                    result.add(new Actor(name, fallbackRole, ""));
                }
            }
        }
    }

    private static void parseRatings(JSONObject data, String fallbackValue, String fallbackSource,
                                     List<Rating> result) {
        if (data == null) return;
        Set<String> seen = new LinkedHashSet<>();
        Object raw = data.opt("ratings");
        if (raw instanceof JSONArray) {
            JSONArray values = (JSONArray) raw;
            for (int index = 0; index < values.length() && result.size() < 6; index++) {
                JSONObject row = values.optJSONObject(index);
                if (row == null) continue;
                addRating(result, seen, first(row, "source", "name", "site"),
                        first(row, "value", "rating", "score"));
            }
        } else if (raw instanceof JSONObject) {
            JSONObject values = (JSONObject) raw;
            JSONArray names = values.names();
            if (names != null) for (int index = 0; index < names.length() && result.size() < 6; index++) {
                String source = names.optString(index, "");
                Object value = values.opt(source);
                String ratingValue = value instanceof JSONObject
                        ? first((JSONObject) value, "value", "rating", "score")
                        : (value == null ? "" : String.valueOf(value));
                addRating(result, seen, source, ratingValue);
            }
        }
        addRating(result, seen, "IMDb", first(data, "imdbRating", "imdb_rating"));
        addRating(result, seen, "TMDB", first(data, "tmdbRating", "tmdb_rating", "vote_average"));
        addRating(result, seen, "Rotten Tomatoes", first(data, "rottenTomatoesRating", "rotten_tomatoes_rating"));
        addRating(result, seen, fallbackSource, fallbackValue);
    }

    private static void addRating(List<Rating> result, Set<String> seen, String source, String value) {
        String cleanValue = value == null ? "" : value.trim();
        String cleanSource = source == null ? "" : source.trim();
        if (!isDisplayableRating(cleanSource, cleanValue)) return;
        String key = cleanSource.toLowerCase(Locale.US);
        if (seen.add(key)) result.add(new Rating(cleanSource, cleanValue));
    }

    /** Ratings are shown only when the upstream payload identifies a source and a sane score. */
    static boolean isDisplayableRating(String source, String value) {
        String cleanSource = source == null ? "" : source.trim();
        String cleanValue = value == null ? "" : value.trim();
        if (cleanSource.isEmpty() || cleanValue.isEmpty()) return false;
        String normalizedSource = cleanSource.toLowerCase(Locale.US);
        if ("source".equals(normalizedSource) || "unknown".equals(normalizedSource)
                || "المصدر".equals(cleanSource) || "غير معروف".equals(cleanSource)) return false;

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:[.,]\\d+)?)").matcher(cleanValue);
        if (!matcher.find()) return false;
        String numeric = matcher.group(1).replace(',', '.');
        try {
            double score = Double.parseDouble(numeric);
            boolean hundredPoint = cleanValue.contains("%")
                    || normalizedSource.contains("rotten")
                    || normalizedSource.contains("tomato")
                    || normalizedSource.contains("metacritic")
                    || normalizedSource.contains("روتن");
            double maximum = hundredPoint ? 100d : 10d;
            return score > 0d && score <= maximum;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int naturalNumber(String value) {
        if (value == null) return Integer.MAX_VALUE;
        int start = -1;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isDigit(value.charAt(index))) {
                start = index;
                break;
            }
        }
        if (start < 0) return Integer.MAX_VALUE;
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        try {
            int parsed = Integer.parseInt(value.substring(start, end));
            return parsed > 0 ? parsed : Integer.MAX_VALUE - 1;
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }
}
