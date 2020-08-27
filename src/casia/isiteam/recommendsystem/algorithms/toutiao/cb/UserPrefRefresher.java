package casia.isiteam.recommendsystem.algorithms.toutiao.cb;

import casia.isiteam.recommendsystem.algorithms.RecommendAlgorithm;
import casia.isiteam.recommendsystem.model.Item;
import casia.isiteam.recommendsystem.model.ItemLog;
import casia.isiteam.recommendsystem.model.User;
import casia.isiteam.recommendsystem.utils.ConfigGetKit;
import casia.isiteam.recommendsystem.utils.DBKit;
import casia.isiteam.recommendsystem.utils.RecommendKit;
import casia.isiteam.recommendsystem.utils.TFIDF;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.ansj.app.keyword.Keyword;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * 用户偏好更新器
 * 当用户浏览新的信息项时，对用户的偏好列表（pref_list）进行更新
 */
@SuppressWarnings("unchecked")
public class UserPrefRefresher {

    private static final Logger logger = LogManager.getLogger(LogManager.ROOT_LOGGER_NAME);

    // TFIDF算法提取关键词的次数
    private static final int KEY_WORDS_NUM = ConfigGetKit.getInt("TFIDFKeywordsNum");
    // 用户偏好每日衰减系数
    private static final double decayNum = ConfigGetKit.getDouble("decayNum");

    /**
     * 定期根据用户前一天的浏览记录，对用户的偏好关键词列表TD-IDF值进行衰减后，将用户前一天看的信息项的关键词及TD-IDF值更新到偏好列表中。
     * @param userIDs 用户ID列表
     */
    public void refresher(Collection<Long> userIDs) {

        // 对用户偏好为 null 的设置默认偏好
        List<User> users = DBKit.getUserPrefList(userIDs);
        for (User user : users) {
            if (user.getPref_list() == null || user.getPref_list().equals("")) {
                String defaultPrefList = RecommendKit.getDefaultPrefList();
                DBKit.updateUserPrefList(defaultPrefList, user.getId());
            }
        }

        // 先对用户偏好关键词列表的TD-IDF值进行衰减更新
        decayUserPref(userIDs);

        // 当日用户浏览记录  用户ID - 浏览信息项的ID列表
        Map<Long, ArrayList<Long>> userTodayBrowsedMap = getTodayBrowsedMap(userIDs);
        // 仅对当日有浏览记录的用户偏好进行更新；当日无用户浏览记录，则无需后面的更新步骤
        if (userTodayBrowsedMap.size() == 0) {
            logger.info("当日无用户浏览记录，用户偏好无需更新。");
            return;
        }

        // 获取当日活跃用户的偏好  用户ID - 偏好列表 pre_list
        Map<Long, String> userPrefListMap = RecommendKit.getUserPreListMap(userTodayBrowsedMap.keySet());
        // 获取 信息项ID - 信息项模块ID 与 信息项ID - 信息项关键词列表（两种）
        Map<String, Object> itemsMap = getItemsTFIDFMap(userTodayBrowsedMap);

        // 遍历用户浏览记录，更新用户偏好关键词列表
        // 外层循环：针对每个用户
        for (Long userID : userTodayBrowsedMap.keySet()) {

            // 获取用户偏好
            Map<String, Object> map = JSONObject.parseObject(userPrefListMap.get(userID));
            // 内层循环：遍历用户看过的每个信息项，将信息项的关键词列表和TD-IDF值更新到用户偏好中
            ArrayList<Long> itemIDs = userTodayBrowsedMap.get(userID);
            for (Long itemID : itemIDs) {
                String moduleName = (String) itemsMap.get(itemID + "-ModuleName");
                // 获取该用户在该模块下的偏好
                Map<String, Object> moduleMap = (Map<String, Object>) map.get(moduleName);
                // 获取信息项的 关键词和TF-IDF值
                List<Keyword> keywords = (List<Keyword>) itemsMap.get(itemID.toString());
                for (Keyword keyword : keywords) {
                    String word = keyword.getName();
                    if (moduleMap.containsKey(word)) {
                        moduleMap.put(word, Double.parseDouble(moduleMap.get(word).toString()) + keyword.getScore());
                    } else {
                        moduleMap.put(word, keyword.getScore());
                    }
                }
            }

            // 更新 userPrefListMap 的 pref_list
            userPrefListMap.put(userID, JSON.toJSONString(map));
        }

        // 将更新好的偏好关键词列表存入表中
        for (Long userID : userPrefListMap.keySet()) {
            DBKit.updateUserPrefList(userPrefListMap.get(userID), userID);
        }
    }

    /**
     * 对用户偏好进行衰减更新 具体实现
     */
    public void decayUserPref(Collection<Long> userIDs) {

        // 获取用户偏好
        List<User> users = DBKit.getUserPrefList(userIDs);

        for (User user : users) {
            // key - 信息项模块id， value - 关键词偏好列表Map
            Map<String, Object> map = JSONObject.parseObject(user.getPref_list());

            // 待删除的关键词列表
            List<String> keywordsToDelete = new ArrayList<>();

            for (String moduleName : map.keySet()) {
                // key - 关键词， value - TF-IDF值
                Map<String, Object> keywordsMap = (Map<String, Object>) map.get(moduleName);
                // 更新每个关键词衰减后的TF-IDF值
                for (String key : keywordsMap.keySet()) {
                    double result = Double.parseDouble(keywordsMap.get(key).toString()) * decayNum;
                    // 剔除低于阈值的关键词
                    if (result < 15) {
                        keywordsToDelete.add(key);
                    } else {
                        keywordsMap.put(key, result);
                    }
                }

                for (String keyword : keywordsToDelete) {
                    keywordsMap.remove(keyword);
                }
            }

            // 更新表中的用户偏好
            DBKit.updateUserPrefList(JSON.toJSONString(map), user.getId());
        }
    }

    /**
     * 提取出当日有浏览行为的用户ID及其浏览过的信息项ID列表
     */
    public Map<Long, ArrayList<Long>> getTodayBrowsedMap(Collection<Long> userIDs) {
        Map<Long, ArrayList<Long>> map = new HashMap<>();

        List<ItemLog> todayBrowsedList = DBKit.getBrowsedItemsByDate(RecommendKit.getSpecificDayFormat(0), RecommendAlgorithm.TOUTIAO);
        for (ItemLog itemLog : todayBrowsedList) {
            if (!userIDs.contains(itemLog.getUser_id())) {
                continue;
            }

            if (!map.containsKey(itemLog.getUser_id())) {
                map.put(itemLog.getUser_id(), new ArrayList<>());
            }
            map.get(itemLog.getUser_id()).add(itemLog.getRef_data_id());
        }

        return map;
    }

    /**
     * 将当日所有被浏览过的信息项提取出来，进行TF-IDF求值操作，并对用户偏好关键词列表进行更新
     */
    public Map<String, Object> getItemsTFIDFMap(Map<Long, ArrayList<Long>> userTodayBrowsedMap) {
        Map<String, Object> map = new HashMap<>();

        List<Item> itemList = DBKit.getItemsByIDs(getTodayBrowsedItemsSet(userTodayBrowsedMap));
        for (Item item : itemList) {
            map.put(String.valueOf(item.getId()), TFIDF.getKeywordsByTFIDE(item.getInfoTitle(), item.getInfoDesc(), KEY_WORDS_NUM));
            map.put(item.getId() + "-ModuleName", item.getClassifySubName());
        }

        return map;
    }

    /**
     * 获取当日浏览的信息项ID集合
     */
    public Set<Long> getTodayBrowsedItemsSet(Map<Long, ArrayList<Long>> map) {
        Set<Long> itemIDsSet = new HashSet<>();
        for (ArrayList<Long> itemIDs : map.values()) {
            itemIDsSet.addAll(itemIDs);
        }

        return itemIDsSet;
    }

}