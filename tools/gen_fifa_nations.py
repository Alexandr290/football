# -*- coding: utf-8 -*-
import os

root = r"C:\Users\ХозяиН\Downloads\football-main\src\main\resources\fifa"
os.makedirs(root, exist_ok=True)


def xi(gk, dfs, mfs, fws, bench=None):
    out = [(gk[0], gk[1], "GK", True)]
    for p in dfs:
        out.append((p[0], p[1], "DF", True))
    for p in mfs:
        out.append((p[0], p[1], "MF", True))
    for p in fws:
        out.append((p[0], p[1], "FW", True))
    if bench:
        for p in bench:
            out.append((p[0], p[1], p[2], False))
    return out


nations = []


def add(code, name, name_ru, confed, rank, group, primary, secondary, squad):
    nations.append((code, name, name_ru, confed, rank, group, primary, secondary, squad))


add("MEX", "Mexico", "Мексика", "CONCACAF", 15, "A", "&a", "&f",
    xi(("Ochoa", 13), [("Montes", 3), ("Araujo", 2), ("Sanchez", 19), ("Gallardo", 23)],
       [("Edson Alvarez", 4), ("Romero", 7), ("Pineda", 10)],
       [("Jimenez", 9), ("Raul Jimenez", 11), ("Vega", 21)],
       [("Malagon", 1, "GK"), ("Antuna", 17, "FW"), ("Rodriguez", 8, "MF")]))
add("RSA", "South Africa", "ЮАР", "CAF", 55, "A", "&e", "&0",
    xi(("Williams", 1), [("Mudau", 2), ("Mokoena", 4), ("Kekana", 5), ("Modiba", 6)],
       [("Zwane", 10), ("Sithole", 8), ("Mofokeng", 12)],
       [("Tau", 7), ("Foster", 9), ("Mabasa", 11)],
       [("Williams Jr", 16, "GK"), ("Modiba B", 15, "DF")]))
add("KOR", "South Korea", "Южная Корея", "AFC", 23, "A", "&c", "&0",
    xi(("Kim Seung-gyu", 1), [("Kim Min-jae", 4), ("Kim Young-gwon", 19), ("Lee Ki-je", 3), ("Kim Moon-hwan", 15)],
       [("Hwang In-beom", 6), ("Lee Jae-sung", 10), ("Son Heung-min", 7)],
       [("Hwang Hee-chan", 11), ("Cho Gue-sung", 9), ("Lee Kang-in", 18)],
       [("Jo Hyeon-woo", 21, "GK"), ("Jung Woo-young", 5, "MF"), ("Oh Hyeon-gyu", 16, "FW")]))
add("CZE", "Czechia", "Чехия", "UEFA", 31, "A", "&c", "&f",
    xi(("Stanek", 1), [("Coufal", 2), ("Holes", 3), ("Krejci", 4), ("Jurasek", 5)],
       [("Soucek", 22), ("Kral", 8), ("Cerny", 17)],
       [("Schick", 10), ("Chory", 9), ("Hlozek", 11)],
       [("Kovar", 16, "GK"), ("Provod", 12, "MF"), ("Lingr", 14, "FW")]))
add("CAN", "Canada", "Канада", "CONCACAF", 41, "B", "&c", "&f",
    xi(("Crevau", 1), [("Johnston", 2), ("Miller", 5), ("Waterman", 4), ("Laryea", 22)],
       [("Eustaquio", 6), ("Kone", 13), ("David", 10)],
       [("Davies", 19), ("Larin", 17), ("Buchanan", 11)],
       [("Borjan", 18, "GK"), ("Osorio", 8, "MF"), ("Ugbo", 9, "FW")]))
add("BIH", "Bosnia and Herzegovina", "Босния и Герцеговина", "UEFA", 74, "B", "&9", "&e",
    xi(("Vasilj", 1), [("Kolasinac", 5), ("Ahmedhodzic", 4), ("Gazibegovic", 2), ("Civic", 3)],
       [("Pjanić", 10), ("Hadziahmetovic", 6), ("Demirovic", 8)],
       [("Dzeko", 11), ("Prevljak", 9), ("Menalo", 7)],
       [("Sehic", 12, "GK"), ("Hajradinovic", 14, "MF"), ("Vrančić", 15, "MF")]))
add("QAT", "Qatar", "Катар", "AFC", 35, "B", "&1", "&f",
    xi(("Al Sheeb", 1), [("Khoukhi", 16), ("Salman", 5), ("Hassan", 2), ("Madibo", 14)],
       [("Hatem", 6), ("Al Haydos", 10), ("Afif", 11)],
       [("Almoez Ali", 19), ("Muntari", 9), ("Boudiaf", 12)],
       [("Barsham", 22, "GK"), ("Al Rawahi", 8, "MF")]))
add("SUI", "Switzerland", "Швейцария", "UEFA", 19, "B", "&c", "&f",
    xi(("Sommer", 1), [("Widmer", 3), ("Akanji", 5), ("Rodriguez", 13), ("Vargas", 17)],
       [("Xhaka", 10), ("Freuler", 8), ("Rieder", 16)],
       [("Embolo", 7), ("Ndoye", 11), ("Shaqiri", 23)],
       [("Kobel", 21, "GK"), ("Zakaria", 6, "MF"), ("Amdouni", 9, "FW")]))
add("BRA", "Brazil", "Бразилия", "CONMEBOL", 5, "C", "&a", "&e",
    xi(("Alisson", 1), [("Danilo", 2), ("Marquinhos", 4), ("Gabriel Magalhaes", 3), ("Carlos Augusto", 6)],
       [("Casemiro", 5), ("Bruno Guimaraes", 8), ("Rodrygo", 10)],
       [("Vinicius Jr", 7), ("Richarlison", 9), ("Raphinha", 11)],
       [("Ederson", 23, "GK"), ("Endrick", 16, "FW"), ("Andreas Pereira", 17, "MF"), ("Bremer", 14, "DF")]))
add("MAR", "Morocco", "Марокко", "CAF", 14, "C", "&c", "&a",
    xi(("Bono", 1), [("Hakimi", 2), ("Aguerd", 5), ("Saiss", 6), ("Mazraoui", 3)],
       [("Amrabat", 4), ("Ounahi", 8), ("Ziyech", 7)],
       [("En-Nesyri", 19), ("Boufal", 17), ("Adli", 10)],
       [("Munir", 12, "GK"), ("Sabiri", 11, "MF"), ("Hamdallah", 9, "FW")]))
add("HAI", "Haiti", "Гаити", "CONCACAF", 87, "C", "&9", "&c",
    xi(("Placide", 1), [("Arcus", 2), ("Alceus", 4), ("Ade", 5), ("Mephors", 3)],
       [("Attys", 6), ("Pierrot", 10), ("Nazon", 9)],
       [("Deori", 7), ("Louicius", 11), ("Etienne", 8)],
       [("Louis", 16, "GK"), ("Jean", 14, "MF")]))
add("SCO", "Scotland", "Шотландия", "UEFA", 27, "C", "&1", "&9",
    xi(("Gordon", 1), [("Ralston", 2), ("Hendry", 4), ("McKenna", 5), ("Robertson", 3)],
       [("McTominay", 8), ("Gilmour", 14), ("McGinn", 7)],
       [("Adams", 9), ("Dykes", 10), ("Christie", 11)],
       [("Gunn", 12, "GK"), ("McLean", 6, "MF"), ("Shankland", 19, "FW")]))
add("USA", "United States", "США", "CONCACAF", 16, "D", "&9", "&f",
    xi(("Turner", 1), [("Dest", 2), ("Richards", 3), ("Zimmerman", 4), ("Robinson", 5)],
       [("Adams", 6), ("McKennie", 8), ("Reyna", 14)],
       [("Pulisic", 10), ("Balogun", 9), ("Weah", 11)],
       [("Steffen", 12, "GK"), ("Musah", 15, "MF"), ("Pepi", 16, "FW")]))
add("AUS", "Australia", "Австралия", "AFC", 24, "D", "&e", "&a",
    xi(("Ryan", 1), [("Degenek", 2), ("Souttar", 4), ("Rowles", 5), ("Behich", 3)],
       [("Irvine", 6), ("Metcalfe", 8), ("McGree", 10)],
       [("Boyle", 7), ("Duke", 9), ("Goodwin", 11)],
       [("Redmayne", 12, "GK"), ("Hrustic", 14, "MF"), ("Maclaren", 15, "FW")]))
add("PAR", "Paraguay", "Парагвай", "CONMEBOL", 48, "D", "&c", "&f",
    xi(("Coronel", 1), [("Alderete", 2), ("Gomez", 4), ("Balbuena", 5), ("Espinoza", 3)],
       [("Almiron", 10), ("Villasanti", 6), ("Cardozo", 8)],
       [("Sanabria", 9), ("Enciso", 11), ("Sosa", 7)],
       [("Aguilar", 12, "GK"), ("Romero", 14, "MF")]))
add("TUR", "Türkiye", "Турция", "UEFA", 28, "D", "&c", "&f",
    xi(("Gunok", 1), [("Kadioglu", 3), ("Demiral", 4), ("Bardakci", 5), ("Muldur", 2)],
       [("Calhanoglu", 10), ("Yuksek", 6), ("Guler", 8)],
       [("Yilmaz", 17), ("Akturkoglu", 7), ("Yildiz", 11)],
       [("Bayindir", 12, "GK"), ("Ozcan", 14, "MF"), ("Akgun", 9, "FW")]))
add("GER", "Germany", "Германия", "UEFA", 12, "E", "&f", "&0",
    xi(("Neuer", 1), [("Kimmich", 6), ("Rudiger", 2), ("Tah", 4), ("Mittelstadt", 3)],
       [("Andrich", 8), ("Goretzka", 18), ("Musiala", 10)],
       [("Sane", 19), ("Havertz", 7), ("Wirtz", 17)],
       [("ter Stegen", 22, "GK"), ("Gundogan", 21, "MF"), ("Fullkrug", 9, "FW"), ("Schlotterbeck", 11, "FW")]))
add("CUW", "Curaçao", "Кюрасао", "CONCACAF", 90, "E", "&9", "&e",
    xi(("Room", 1), [("Martina", 2), ("Gaari", 4), ("Bacuna", 5), ("Conte", 3)],
       [("Bacuna L", 6), ("Rosa", 8), ("Janga", 10)],
       [("Janga R", 9), ("Maria", 7), ("Roemer", 11)],
       [("Ogenia", 12, "GK"), ("Antonisse", 14, "FW")]))
add("CIV", "Ivory Coast", "Кот-д’Ивуар", "CAF", 37, "E", "&e", "&a",
    xi(("Yehovan", 1), [("Aurier", 17), ("Ndicka", 21), ("Bailly", 5), ("Konan", 3)],
       [("Seri", 6), ("Sangare", 18), ("Kessie", 8)],
       [("Pepe", 19), ("Hallerk", 9), ("Gradel", 15)],
       [("Gbohouo", 16, "GK"), ("Kragbe", 10, "MF"), ("Bamba", 11, "FW")]))
add("ECU", "Ecuador", "Эквадор", "CONMEBOL", 30, "E", "&e", "&9",
    xi(("Galindez", 1), [("Estupinan", 2), ("Torres", 3), ("Hincapie", 4), ("Preciado", 5)],
       [("Caicedo", 6), ("Franco", 8), ("Paez", 10)],
       [("Valencia", 11), ("Rodriguez", 9), ("Mena", 7)],
       [("Dominguez", 12, "GK"), ("Sarmiento", 14, "MF"), ("Plata", 15, "FW")]))
add("NED", "Netherlands", "Нидерланды", "UEFA", 7, "F", "&6", "&f",
    xi(("Verbruggen", 1), [("Dumfries", 22), ("Van Dijk", 4), ("De Ligt", 3), ("Ake", 5)],
       [("Reijnders", 6), ("Gravenberch", 8), ("Gakpo", 11)],
       [("Depay", 10), ("Simons", 7), ("Brobbey", 9)],
       [("Flekken", 13, "GK"), ("Schouten", 14, "MF"), ("Malen", 18, "FW")]))
add("JPN", "Japan", "Япония", "AFC", 18, "F", "&f", "&0",
    xi(("Suzuki", 1), [("Ito", 2), ("Tomiyasu", 4), ("Itakura", 5), ("Nagatomo", 3)],
       [("Endo", 6), ("Kamada", 8), ("Kubo", 10)],
       [("Mitoma", 7), ("Maeda", 9), ("Doan", 11)],
       [("Gonda", 12, "GK"), ("Tanaka", 14, "MF"), ("Ueda", 15, "FW")]))
add("SWE", "Sweden", "Швеция", "UEFA", 22, "F", "&e", "&9",
    xi(("Olsen", 1), [("Krafth", 2), ("Lindelof", 4), ("Hien", 5), ("Augustinsson", 3)],
       [("Kulusevski", 10), ("Olsson", 8), ("Svanberg", 6)],
       [("Isak", 9), ("Gyokeres", 11), ("Forsberg", 7)],
       [("Johansson", 12, "GK"), ("Karlsson", 14, "MF"), ("Elanga", 15, "FW")]))
add("TUN", "Tunisia", "Тунис", "CAF", 40, "F", "&c", "&f",
    xi(("Dahmen", 1), [("Talbi", 2), ("Meriah", 4), ("Bronn", 5), ("Maaloul", 3)],
       [("Skhiri", 6), ("Laidouni", 8), ("Msakni", 10)],
       [("Hazem", 7), ("Sliti", 9), ("Ben Slimane", 11)],
       [("Ben Said", 16, "GK"), ("Chaouat", 14, "FW")]))
add("BEL", "Belgium", "Бельгия", "UEFA", 8, "G", "&c", "&e",
    xi(("Casteels", 1), [("Castagne", 2), ("Debast", 4), ("Theate", 5), ("Faes", 3)],
       [("Onana", 6), ("De Ketelaere", 8), ("De Bruyne", 7)],
       [("Lukaku", 9), ("Doku", 11), ("Trossard", 10)],
       [("Sels", 12, "GK"), ("Tielemans", 14, "MF"), ("Openda", 15, "FW")]))
add("EGY", "Egypt", "Египет", "CAF", 33, "G", "&c", "&f",
    xi(("Abou Gabal", 1), [("Hegazi", 2), ("Abdelmonem", 4), ("Hamdi", 5), ("Tarek", 3)],
       [("Elneny", 6), ("Fathy", 8), ("Mohamed", 14)],
       [("Salah", 10), ("Marmoush", 9), ("Trézéguet", 11)],
       [("El Shenawy", 16, "GK"), ("Kamal", 15, "MF"), ("Adel", 17, "FW")]))
add("IRN", "Iran", "Иран", "AFC", 20, "G", "&a", "&f",
    xi(("Beiranvand", 1), [("Moharrami", 2), ("Kanaani", 4), ("Khalilzadeh", 5), ("Mohammadi", 3)],
       [("Ezatolahi", 6), ("Ghoddos", 8), ("Jahanbakhsh", 10)],
       [("Taremi", 9), ("Azmoun", 20), ("Torabi", 11)],
       [("Abedzadeh", 12, "GK"), ("Nourollahi", 14, "MF"), ("Ghayedi", 15, "FW")]))
add("NZL", "New Zealand", "Новая Зеландия", "OFC", 104, "G", "&f", "&0",
    xi(("Marinovic", 1), [("Boxall", 2), ("Sutton", 4), ("Payne", 5), ("Surman", 3)],
       [("McGlinchey", 6), ("Lewis", 8), ("Wood", 9)],
       [("Garbett", 10), ("Just", 7), ("Waine", 11)],
       [("Singh", 12, "GK"), ("Rojas", 14, "MF")]))
add("ESP", "Spain", "Испания", "UEFA", 3, "H", "&c", "&e",
    xi(("Unai Simon", 1), [("Carvajal", 2), ("Le Normand", 3), ("Cubarsi", 4), ("Balde", 5)],
       [("Rodri", 16), ("Pedri", 8), ("Gavi", 9)],
       [("Yamal", 19), ("Morata", 7), ("Williams", 11)],
       [("Raya", 13, "GK"), ("Olmo", 10, "MF"), ("Oyarzabal", 15, "FW"), ("Ruiz", 14, "MF")]))
add("CPV", "Cape Verde", "Кабо-Верде", "CAF", 71, "H", "&9", "&c",
    xi(("Vozinha", 1), [("Stopira", 2), ("Lopes", 4), ("Rocha", 5), ("Borges", 3)],
       [("Babanco", 6), ("Rodrigues", 8), ("Duarte", 10)],
       [("Rodrigues G", 9), ("Mendes", 7), ("Teixeira", 11)],
       [("Ket", 12, "GK"), ("Pina", 14, "MF")]))
add("KSA", "Saudi Arabia", "Саудовская Аравия", "AFC", 56, "H", "&a", "&f",
    xi(("Al Owais", 1), [("Al Ghannam", 2), ("Al Bulayhi", 4), ("Al Tambakti", 5), ("Abdulhamid", 3)],
       [("Kanno", 6), ("Al Faraj", 7), ("Al Dawsari", 10)],
       [("Al Shehri", 9), ("Al Buraikan", 11), ("Al Amri", 8)],
       [("Al Rubaie", 12, "GK"), ("Al Najei", 14, "MF")]))
add("URU", "Uruguay", "Уругвай", "CONMEBOL", 11, "H", "&9", "&0",
    xi(("Rochet", 1), [("Varela", 2), ("Araujo", 4), ("Gimenez", 5), ("Olivera", 3)],
       [("Valverde", 6), ("Bentancur", 8), ("De la Cruz", 7)],
       [("Nunez", 11), ("Cavani", 9), ("Pellistri", 10)],
       [("Muslera", 12, "GK"), ("Ugarte", 14, "MF"), ("Darwin", 15, "FW")]))
add("FRA", "France", "Франция", "UEFA", 2, "I", "&9", "&f",
    xi(("Maignan", 1), [("Kounde", 5), ("Saliba", 4), ("Upamecano", 2), ("Hernandez", 22)],
       [("Tchouameni", 6), ("Camavinga", 8), ("Rabiot", 14)],
       [("Mbappe", 10), ("Dembele", 11), ("Griezmann", 7)],
       [("Samba", 16, "GK"), ("Zaire-Emery", 12, "MF"), ("Thuram", 9, "FW")]))
add("SEN", "Senegal", "Сенегал", "CAF", 17, "I", "&a", "&e",
    xi(("Mendy", 1), [("Sabaly", 2), ("Koulibaly", 4), ("Diallo", 5), ("Balloka", 3)],
       [("Gueye", 6), ("Pape Gueye", 8), ("I. Sarr", 14)],
       [("Mane", 10), ("Dia", 9), ("Jackson", 11)],
       [("Dieng", 16, "GK"), ("Camara", 15, "MF"), ("Diatta", 17, "FW")]))
add("NOR", "Norway", "Норвегия", "UEFA", 43, "I", "&c", "&9",
    xi(("Nyland", 1), [("Pedersen", 2), ("Hanche-Olsen", 4), ("Ostigard", 5), ("Meling", 3)],
       [("Aursnes", 6), ("Berge", 8), ("Odegaard", 10)],
       [("Haaland", 9), ("Sorloth", 11), ("Nusa", 7)],
       [("Dyngeland", 12, "GK"), ("Thorstvedt", 14, "MF"), ("Dybvik", 15, "FW")]))
add("IRQ", "Iraq", "Ирак", "AFC", 62, "I", "&a", "&f",
    xi(("Jalal", 1), [("Attwan", 2), ("Adnan", 4), ("Tahseen", 5), ("Mhawi", 3)],
       [("Hussein", 6), ("Bayesh", 8), ("Amoura", 10)],
       [("Hussein A", 9), ("Yahia", 11), ("Iqbal", 7)],
       [("Hassan", 12, "GK"), ("Ali", 14, "MF")]))
add("ARG", "Argentina", "Аргентина", "CONMEBOL", 1, "J", "&b", "&f",
    xi(("Martinez", 23), [("Molina", 2), ("Romero", 13), ("Otamendi", 19), ("Tagliafico", 3)],
       [("De Paul", 7), ("Mac Allister", 20), ("Enzo Fernandez", 24)],
       [("Messi", 10), ("Alvarez", 9), ("Di Maria", 11)],
       [("Rulli", 12, "GK"), ("Paredes", 5, "MF"), ("Lautaro", 22, "FW"), ("Gonzalez", 15, "FW")]))
add("ALG", "Algeria", "Алжир", "CAF", 34, "J", "&a", "&f",
    xi(("MBolhi", 1), [("Atal", 2), ("Bensebaini", 4), ("Mandi", 5), ("Benlamri", 3)],
       [("Bennacer", 6), ("Belaili", 8), ("Mahrez", 7)],
       [("Boudaoui", 10), ("Slimani", 9), ("Ounas", 11)],
       [("Oukidja", 16, "GK"), ("Zorgane", 14, "MF"), ("Amoura", 15, "FW")]))
add("AUT", "Austria", "Австрия", "UEFA", 25, "J", "&c", "&f",
    xi(("Pentz", 1), [("Posch", 2), ("Lienhart", 4), ("Wober", 5), ("Mwene", 3)],
       [("Schlager", 6), ("Laimer", 8), ("Baumgartner", 10)],
       [("Sabitzer", 9), ("Arnautovic", 7), ("Gregoritsch", 11)],
       [("Schlager G", 12, "GK"), ("Seiwald", 14, "MF")]))
add("JOR", "Jordan", "Иордания", "AFC", 68, "J", "&c", "&f",
    xi(("Al Essawi", 1), [("Haddad", 2), ("Al Arab", 4), ("Al Ajalin", 5), ("Abu Hasheesh", 3)],
       [("Al Naimat", 6), ("Al Mardi", 8), ("Olwan", 10)],
       [("Al Naimat Y", 9), ("Al Attar", 11), ("Al Rashdan", 7)],
       [("Abu Laila", 12, "GK"), ("Al Dardour", 14, "FW")]))
add("POR", "Portugal", "Португалия", "UEFA", 6, "K", "&c", "&a",
    xi(("Costa", 22), [("Cancelo", 2), ("Dias", 4), ("Inacio", 5), ("Mendes", 3)],
       [("Vitinha", 6), ("Bernardo", 8), ("Bruno", 10)],
       [("Ronaldo", 7), ("Felix", 11), ("Leao", 17)],
       [("Sa", 12, "GK"), ("Palhinha", 14, "MF"), ("Neto", 15, "FW"), ("Ramos", 9, "FW")]))
add("COD", "DR Congo", "ДР Конго", "CAF", 61, "K", "&9", "&c",
    xi(("Mpasi", 1), [("Masuaku", 2), ("Mbemba", 4), ("Tisserand", 5), ("Kalulu", 3)],
       [("Moutoussamy", 6), ("Bongonda", 8), ("Bakambu", 10)],
       [("Bolasie", 7), ("Wissa", 9), ("Mbokani", 11)],
       [("Bertin", 12, "GK"), ("Kakuta", 14, "MF")]))
add("UZB", "Uzbekistan", "Узбекистан", "AFC", 58, "K", "&9", "&f",
    xi(("Yusupov", 1), [("Ashurmatov", 2), ("Sayfiev", 4), ("Ergashev", 5), ("Urunov", 3)],
       [("Masharipov", 6), ("Shomurodov", 8), ("Fayzullaev", 10)],
       [("Shomurodov E", 9), ("Ashurmatov A", 7), ("Urunov A", 11)],
       [("Nematov", 12, "GK"), ("Sergeev", 14, "FW")]))
add("COL", "Colombia", "Колумбия", "CONMEBOL", 13, "K", "&e", "&9",
    xi(("Vargas", 1), [("Munoz", 2), ("Lucumi", 4), ("Cuesta", 5), ("Mojica", 3)],
       [("Rios", 6), ("Lerma", 8), ("James", 10)],
       [("Diaz", 7), ("Cordoba", 9), ("Arias", 11)],
       [("Ospina", 12, "GK"), ("Uribe", 14, "MF"), ("Borja", 15, "FW")]))
add("ENG", "England", "Англия", "UEFA", 4, "L", "&f", "&c",
    xi(("Pickford", 1), [("Walker", 2), ("Stones", 5), ("Guehi", 6), ("Lewis", 3)],
       [("Rice", 4), ("Bellingham", 10), ("Foden", 8)],
       [("Saka", 7), ("Kane", 9), ("Palmer", 11)],
       [("Ramsdale", 13, "GK"), ("Mainoo", 14, "MF"), ("Watkins", 15, "FW"), ("Gordon", 16, "FW")]))
add("CRO", "Croatia", "Хорватия", "UEFA", 10, "L", "&c", "&9",
    xi(("Livakovic", 1), [("Stanisic", 2), ("Gvardiol", 4), ("Sutalo", 5), ("Sosa", 3)],
       [("Modric", 10), ("Kovacic", 8), ("Brozovic", 11)],
       [("Perisic", 14), ("Kramaric", 9), ("Petkovic", 16)],
       [("Ivusic", 12, "GK"), ("Majer", 7, "MF"), ("Budimir", 15, "FW")]))
add("GHA", "Ghana", "Гана", "CAF", 60, "L", "&e", "&0",
    xi(("Wollacott", 1), [("Baba", 2), ("Amartey", 4), ("Djiku", 5), ("Rahman", 3)],
       [("Partey", 6), ("Kudus", 8), ("J. Ayew", 10)],
       [("A. Ayew", 9), ("Williams", 7), ("Bukari", 11)],
       [("Ati-Zigi", 12, "GK"), ("Samed", 14, "MF"), ("Inkoom", 15, "FW")]))
add("PAN", "Panama", "Панама", "CONCACAF", 45, "L", "&c", "&f",
    xi(("Mejia", 1), [("Escobar", 2), ("Cordoba", 4), ("Cummings", 5), ("Davis", 3)],
       [("Godoy", 6), ("Carrasquilla", 8), ("Waterman", 10)],
       [("Rodriguez", 9), ("Fajardo", 11), ("Barcenas", 7)],
       [("Mosquera", 12, "GK"), ("I. Diaz", 14, "MF")]))

assert len(nations) == 48, len(nations)

lines = [
    "# FIFA World Cup 2026 national teams catalog",
    "# Static snapshot for AdvancedSoccer",
    "nations:",
]
for code, name, name_ru, confed, rank, group, primary, secondary, squad in sorted(nations, key=lambda x: x[4]):
    lines.append(f"  {code}:")
    lines.append(f'    name: "{name}"')
    lines.append(f'    name-ru: "{name_ru}"')
    lines.append(f"    confederation: {confed}")
    lines.append(f"    fifa-rank: {rank}")
    lines.append(f"    group: {group}")
    lines.append(f'    primary-color: "{primary}"')
    lines.append(f'    secondary-color: "{secondary}"')
    lines.append("    squad:")
    for pname, num, pos, starter in squad:
        lines.append(
            f'      - {{name: "{pname}", number: {num}, pos: {pos}, starter: {str(starter).lower()}}}'
        )

path = os.path.join(root, "nations.yml")
with open(path, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("Wrote", path, "nations=", len(nations))
